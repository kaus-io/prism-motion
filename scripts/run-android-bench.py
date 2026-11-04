#!/usr/bin/env python3
"""运行当前 Android bench，校验完整性并保存结果。 / Runs this project's Android bench and validates complete output."""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import random
import re
import selectors
import shlex
import subprocess
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.zxhhyj.prism.motion.bench.android"
METADATA = ("repeat", "trial", "run_id", "seed")
VERSION_FIELDS = ("app_version", "bench_version", "version_code")
VERSION_ASSET = "assets/prism-bench-version.json"
ALPHA_VERSION = re.compile(r"alpha(0[1-9]|[1-9][0-9]+)")


def command(argv, **kwargs):
    """任一步失败即报错，不吞掉 adb/构建错误。 / Propagates every adb/build failure."""
    return subprocess.run(argv, check=True, text=True, **kwargs)


def parse_counts(value):
    """保留零实例基线并拒绝重复或越界计数。 / Keeps zero-instance baselines and rejects duplicate or out-of-range counts."""
    try:
        counts = [int(part) for part in value.split(",")]
    except ValueError as error:
        raise argparse.ArgumentTypeError("counts must be unique integers from 0 to 64") from error
    if not counts or any(count < 0 or count > 64 for count in counts) or len(counts) != len(set(counts)):
        raise argparse.ArgumentTypeError("counts must be unique integers from 0 to 64")
    return counts


def positive_int(value):
    """验证正整数参数。 / Validates positive integer arguments."""
    result = int(value)
    if result < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return result


def nonnegative_int(value):
    """验证允许零值的参数。 / Validates arguments that may be zero."""
    result = int(value)
    if result < 0:
        raise argparse.ArgumentTypeError("must be a nonnegative integer")
    return result


def argument_parser():
    """集中定义运行参数与安全默认值。 / Defines runner options and safe defaults."""
    parser = argparse.ArgumentParser(description=__doc__, epilog=(
        "OUT overrides the CSV path; default: benchmarks/<APK app_version>/runs/<first_run_id>/raw.csv; "
        "unversioned debug: system temporary directory. The manifest defaults to beside the CSV."))
    parser.add_argument("serial", help="explicit connected ADB device serial")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--variant", choices=("debug", "benchmark"), default="debug",
                        help="APK/build variant (default: debug)")
    parser.add_argument("--counts", type=parse_counts, default="1,8,16")
    parser.add_argument("--measure-ms", type=positive_int, default=2500)
    parser.add_argument("--warmup-ms", type=nonnegative_int, default=1000)
    parser.add_argument("--timeout", type=positive_int, default=180)
    parser.add_argument("--repeats", type=positive_int, default=1)
    parser.add_argument("--seed", "--random-seed", type=int, default=0,
                        help="recorded seed for shuffling trials independently in each round (default: 0)")
    parser.add_argument("--isolate-scenarios", action="store_true",
                        help="start a fresh app process for each (asset, count), not each asset")
    parser.add_argument("--allow-invalid", action="store_true",
                        help="allow completed valid=false scenarios; infrastructure errors still fail")
    parser.add_argument("--manifest", help="override manifest JSON path (default: beside CSV); records arguments, versions, trial order and APK SHA256")
    parser.add_argument("--expect-app-version", help="expected benchmark APK app version (default: root version property)")
    parser.add_argument("--expect-bench-version", help="expected benchmark protocol version (default: root benchVersion property)")
    return parser


def trial_plan(assets, counts, repeats, seed, isolate_scenarios):
    """按种子逐轮打乱完整场景，试验编号跨轮递增。 / Shuffles the full scenario list per seeded round with global trial numbering."""
    scenarios = [(asset, [count]) for asset in assets for count in counts] if isolate_scenarios else [
        (asset, list(counts)) for asset in assets]
    rng = random.Random(seed)
    plan = []
    for repeat in range(1, repeats + 1):
        order = list(scenarios)
        rng.shuffle(order)
        for asset, trial_counts in order:
            plan.append({"repeat": repeat, "trial": len(plan) + 1, "asset": asset, "counts": list(trial_counts)})
    return plan


def apk_assets(apk):
    """检查 KAP 原样打包并返回精确资源名。 / Checks uncompressed KAP packaging and returns exact asset stems."""
    with zipfile.ZipFile(apk) as archive:
        filenames = {Path(info.filename).stem: Path(info.filename).name for info in archive.infolist()
                     if info.filename.startswith("assets/") and info.filename.endswith((".ktx", ".mp4"))}
        compressed_ktx = [info.filename for info in archive.infolist()
                          if info.filename.endswith((".ktx", ".ktx2")) and info.compress_type != zipfile.ZIP_STORED]
        if compressed_ktx:
            raise RuntimeError(f"KAP assets must use noCompress: {compressed_ktx}")
    return filenames


def apk_asset_hashes(apk, selected):
    """在主机上散列实际打包的材料，避免把素材变化误认为性能回归。 / Hashes actual packaged inputs on the host to separate material changes from performance regressions."""
    hashes = {}
    with zipfile.ZipFile(apk) as archive:
        for asset in selected:
            matches = [info for info in archive.infolist() if info.filename.startswith("assets/")
                       and info.filename.endswith((".ktx", ".mp4")) and Path(info.filename).stem == asset]
            if len(matches) != 1:
                raise RuntimeError(f"APK asset stem must identify exactly one material: {asset}")
            digest = hashlib.sha256()
            with archive.open(matches[0]) as handle:
                for block in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(block)
            hashes[asset] = digest.hexdigest()
    return hashes


def apk_versions(apk, required=False):
    """只信任 APK 内烘焙的版本；旧 debug 包保持无版本。 / Trusts only APK-baked versions; legacy debug APKs remain unversioned."""
    with zipfile.ZipFile(apk) as archive:
        copies = archive.namelist().count(VERSION_ASSET)
        if not copies:
            if required:
                raise RuntimeError(f"Benchmark APK is missing required version asset: {VERSION_ASSET}; rebuild the APK")
            return None
        if copies != 1:
            raise RuntimeError(f"APK has duplicate version assets: {VERSION_ASSET}")
        try:
            versions = json.loads(archive.read(VERSION_ASSET))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise RuntimeError(f"Malformed APK version asset: {error}") from error
    if not isinstance(versions, dict) or any(key not in versions for key in VERSION_FIELDS):
        raise RuntimeError(f"APK version asset must contain {VERSION_FIELDS}")
    for key in ("app_version", "bench_version"):
        if not isinstance(versions[key], str) or not ALPHA_VERSION.fullmatch(versions[key]):
            raise RuntimeError(f"APK version asset has invalid {key}: {versions[key]!r}")
    code = versions["version_code"]
    if (type(code) is not int or not 1 <= code <= 2100000000
            or str(code) != versions["app_version"][5:].lstrip("0")):
        raise RuntimeError(f"APK version_code does not match app_version: {versions}")
    return {key: versions[key] for key in VERSION_FIELDS}


def check_expected_versions(args, versions):
    """基准包须匹配声明或显式预期版本，禁止静默安装旧包。 / Requires benchmark APKs to match declared or explicitly expected versions before installation."""
    if args.variant != "benchmark":
        return
    if versions is None:
        raise RuntimeError("Benchmark APK requires baked version metadata")
    declared = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            declared[key.strip()] = value.strip()
    expected = {"app_version": args.expect_app_version or declared.get("version"),
                "bench_version": args.expect_bench_version or declared.get("benchVersion")}
    for key, value in expected.items():
        if not isinstance(value, str) or not ALPHA_VERSION.fullmatch(value):
            raise RuntimeError(f"Missing or invalid expected {key}: {value!r}")
        if versions[key] != value:
            raise RuntimeError(f"Stale benchmark APK {key}: APK={versions[key]!r}; expected={value!r}; rebuild or explicitly select the expected version")


def validate_versions(fields, rows, versions, name):
    """逐行核对 APK 身份，不给缺失或不匹配的数据补标签。 / Checks APK identity on every row without filling missing or mismatched labels."""
    if versions is None or not fields and not rows:
        return
    missing = set(VERSION_FIELDS) - set(fields)
    if missing:
        raise RuntimeError(f"{name} is missing required version columns: {sorted(missing)}")
    for index, row in enumerate(rows, 1):
        for key in VERSION_FIELDS:
            expected = str(versions[key])
            if row.get(key) != expected:
                raise RuntimeError(f"{name} row {index} version mismatch for {key}: {row.get(key)!r}; APK={expected!r}")


def wait_done(adb, run_id, launch, timeout):
    """只接受本次 run-id 的完成事件，有超时且不清全机日志。 / Waits for this run's event with a deadline, without clearing device logs."""
    process = subprocess.Popen(adb + ["logcat", "-v", "raw", "-T", "1", "prism-bench:I", "AndroidRuntime:E", "*:S"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        command(launch, stdout=subprocess.DEVNULL)
        deadline = time.monotonic() + timeout
        pending = b""
        started = False
        while time.monotonic() < deadline:
            events = selector.select(max(0, deadline - time.monotonic()))
            if not events:
                break
            data = os.read(process.stdout.fileno(), 8192)
            if not data:
                raise RuntimeError("adb logcat closed before completion")
            pending += data
            while b"\n" in pending:
                line, pending = pending.split(b"\n", 1)
                text = line.decode(errors="replace")
                if f"BENCH_START {run_id}" in text:
                    started = True
                if started and f"Process: {PACKAGE}," in text:
                    raise RuntimeError("Benchmark process crashed; inspect adb logcat -b crash")
                if f"BENCH_DONE {run_id} " in text:
                    return
                if "SCENARIO_DONE" in text:
                    print(text, flush=True)
        raise TimeoutError(f"No BENCH_DONE for {run_id} in {timeout}s")
    finally:
        selector.close()
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        process.stdout.close()


def read_remote_csv(adb, name, optional=False):
    """独立读取结果文件，可选文件缺失不掩盖 adb 错误。 / Reads each result independently; optional absence never masks adb errors."""
    remote = f"/sdcard/Android/data/{PACKAGE}/files/{name}"
    if optional:
        script = f"if [ -f {shlex.quote(remote)} ]; then cat {shlex.quote(remote)}; fi"
        raw = command(adb + ["shell", script], stdout=subprocess.PIPE).stdout
        if not raw:
            return [], []
    else:
        raw = command(adb + ["exec-out", "cat", remote], stdout=subprocess.PIPE).stdout
    reader = csv.DictReader(io.StringIO(raw))
    if not reader.fieldnames:
        raise RuntimeError(f"{name} has no CSV header")
    required = {"asset", "count"}
    if name == "bench.csv":
        required.add("valid")
    if name == "presents.csv":
        required.update(("instance", "elapsed_ns", "phase"))
    missing = required - set(reader.fieldnames)
    if missing:
        raise RuntimeError(f"{name} is missing required CSV columns: {sorted(missing)}; header={reader.fieldnames}")
    rows = list(reader)
    if any(None in row for row in rows):
        raise RuntimeError(f"{name} has more cells than header columns")
    return reader.fieldnames, rows


def validate_presents(rows, presents, expected):
    """基准变体必须用原始回调精确核对帧数及实例，损坏输出属于基础设施错误。 / Benchmark variants require exact raw-frame and instance agreement; corrupt output is an infrastructure error."""
    grouped, previous = {}, {}
    try:
        for event in presents:
            key = (Path(event["asset"]).stem, int(event["count"]))
            instance, timestamp = int(event["instance"]), int(event["elapsed_ns"])
            stream = key + (instance,)
            if (key not in expected or not 0 <= instance < key[1] or timestamp < 0 or event["phase"] != "measure"
                    or timestamp <= previous.get(stream, -1)):
                raise RuntimeError(f"Corrupt presents.csv event: {event}")
            previous[stream] = timestamp
            grouped.setdefault(key, []).append(instance)
        for row in rows:
            count = int(row["count"])
            if row.get("valid") != "true" or count == 0:
                continue
            key = (Path(row["asset"]).stem, count)
            submitted = int(row["submitted_frames"])
            instances = grouped.get(key, [])
            if len(instances) != submitted or set(instances) != set(range(count)):
                raise RuntimeError(f"presents.csv mismatch for {key}: rows={len(instances)}, submitted_frames={submitted}, "
                                   f"instances={sorted(set(instances))}, expected_instances={count}")
    except (KeyError, TypeError, ValueError) as error:
        raise RuntimeError(f"Malformed presents.csv or submitted_frames: {error}") from error


def collect_trial(adb, trial, filenames, args, versions=None, asset_hashes=None):
    """收集部分输出并保留原始异常，基础设施故障绝不视为有效失败。 / Collects partial output and retains original exceptions; infrastructure failures never become allowed invalid results."""
    errors = []
    launched = False
    try:
        command(adb + ["shell", "am", "force-stop", PACKAGE])
        launched = True
        launch = adb + ["shell", "am", "start", "-W", "-n", PACKAGE + "/.BenchActivity", "--es", "only", trial["asset"],
                        "--es", "counts", ",".join(map(str, trial["counts"])),
                        "--el", "measure_ms", str(args.measure_ms), "--el", "warmup_ms", str(args.warmup_ms),
                        "--es", "run_id", trial["run_id"]]
        wait_done(adb, trial["run_id"], launch, args.timeout)
    except Exception as error:
        errors.append(error)
    datasets = {name: ([], []) for name in ("bench", "samples", "presents")}
    if launched:
        for name in datasets:
            try:
                datasets[name] = read_remote_csv(adb, name + ".csv", optional=name == "presents" and args.variant != "benchmark")
            except Exception as error:
                errors.append(error)
    for name, (fields, rows) in datasets.items():
        try:
            validate_versions(fields, rows, versions, name + ".csv")
        except RuntimeError as error:
            errors.append(error)
    fields, current = datasets["bench"]
    if fields and not {"asset", "count", "valid"}.issubset(fields):
        errors.append(RuntimeError("bench.csv is missing asset/count/valid columns"))
    expected = [(trial["asset"], count) for count in trial["counts"]]
    actual = []
    for row in current:
        if row.get("valid") not in {"true", "false"} or any(value is None for value in row.values()):
            errors.append(RuntimeError(f"Incomplete/malformed scenario row: {row}"))
        try:
            actual.append((Path(row["asset"]).stem, int(row["count"])))
        except (KeyError, TypeError, ValueError) as error:
            errors.append(RuntimeError(f"Malformed scenario row: {row}: {error}"))
    if sorted(actual) != sorted(expected):
        errors.append(RuntimeError(f"Incomplete/duplicate scenarios for {trial['asset']}: {actual}; expected {expected}"))
    if args.variant == "benchmark":
        try:
            validate_presents(current, datasets["presents"][1], expected)
        except RuntimeError as error:
            errors.append(error)
    reason = "; ".join(str(error) for error in errors)
    for asset, count in expected:
        if (asset, count) not in actual:
            current.append({"asset": filenames[asset], "count": str(count), "valid": "false", "error": reason})
    metadata = {key: trial[key] for key in METADATA if key != "seed"} | {"seed": args.seed}
    collected = {}
    for name, (columns, rows) in datasets.items():
        enriched = []
        for row in rows:
            material = {} if asset_hashes is None else {
                "asset_sha256": asset_hashes.get(Path(row.get("asset") or "").stem, "")}
            enriched.append(row | metadata | material)
        collected[name] = columns, enriched
    return collected, errors


def persist_csv(path, columns, rows):
    """合并所有动态列，避免后续试验的新指标丢失。 / Unions all dynamic columns so later trials cannot lose new metrics."""
    for row in rows:
        for key in row:
            if key not in columns:
                columns.append(key)
    for key in METADATA:
        if key not in columns:
            columns.append(key)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def run_trials(adb, plan, filenames, args, output, trace_output, versions=None, asset_hashes=None):
    """每次试验后落盘；预期后端失败可继续，进程或采集异常保存后重抛。 / Persists every trial; expected backend failures continue, while process or collection errors are re-raised after saving."""
    if args.variant == "benchmark" and versions is None:
        raise RuntimeError("Benchmark APK requires baked version metadata before running trials")
    present_output = output.with_name(output.stem + "-presents.csv")
    results = {name: {"path": path, "columns": [], "rows": []} for name, path in
               (("bench", output), ("samples", trace_output), ("presents", present_output))}
    for trial in plan:
        print(f"Trial {trial['trial']}/{len(plan)} repeat={trial['repeat']} asset={trial['asset']} "
              f"counts={','.join(map(str, trial['counts']))} run_id={trial['run_id']}", flush=True)
        datasets, errors = collect_trial(adb, trial, filenames, args, versions, asset_hashes)
        # 即使崩溃、超时或读文件失败，也先保存当前试验。 / Saves the current trial even after a crash, timeout or file-read failure.
        for name, (fields, current) in datasets.items():
            result = results[name]
            result["columns"].extend(field for field in fields if field not in result["columns"])
            result["rows"].extend(current)
            persist_csv(result["path"], result["columns"], result["rows"])
        if errors:
            print(f"Partial results: {output}\nSamples: {trace_output}\nPresents: {present_output}", flush=True)
            for error in errors:
                print(f"Trial error: {error}", flush=True)
            raise errors[0]
        print(f"Completed trial {trial['trial']}: {len(datasets['bench'][1])} rows", flush=True)
    return results["bench"]["rows"]


def write_manifest(path, args, apk, selected, plan, versions=None, asset_hashes=None):
    """仅记录公开运行参数与 APK 校验值，不读取凭据。 / Records only public run arguments and APK checksum, never credentials."""
    digest = hashlib.sha256()
    with apk.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"seed": args.seed, "args": vars(args), "apk": str(apk),
                                "apk_sha256": digest.hexdigest(), "assets": selected, "asset_sha256": asset_hashes or {}, "trials": plan,
                                **(versions if versions is not None else dict.fromkeys(VERSION_FIELDS))},
                               indent=2) + "\n", encoding="utf-8")


def main():
    """验证安装包并运行有限轮数，默认对无效结果返回非零。 / Validates the APK and runs bounded rounds, failing invalid results by default."""
    args = argument_parser().parse_args()
    adb = ["adb", "-s", args.serial]
    command(adb + ["get-state"], stdout=subprocess.DEVNULL)
    if not args.skip_build:
        task = ":bench-android:assemble" + args.variant.capitalize()
        build = [str(ROOT / "gradlew"), "-p", str(ROOT), task, "--console=plain"]
        if os.environ.get("DEMO_ASSETS"):
            build += ["-PdemoAssets=" + os.environ["DEMO_ASSETS"]]
        command(build)
    apk = ROOT / f"bench-android/build/outputs/apk/{args.variant}/bench-android-{args.variant}.apk"
    versions = apk_versions(apk, required=args.variant == "benchmark")
    check_expected_versions(args, versions)
    filenames = apk_assets(apk)
    selected = shlex.split(os.environ.get("ASSETS", "")) or sorted(filenames)
    missing = set(selected) - set(filenames)
    if missing or not selected or len(selected) != len(set(selected)):
        raise RuntimeError(f"ASSETS must be unique exact APK asset stems; missing={sorted(missing)}")
    asset_hashes = apk_asset_hashes(apk, selected)
    plan = trial_plan(selected, args.counts, args.repeats, args.seed, args.isolate_scenarios)
    for trial in plan:
        trial["run_id"] = uuid.uuid4().hex
    # 按 APK 实际版本保留独立运行，旧包只写系统临时目录。 / Retains separate runs by baked APK version; legacy APKs use only system temporary storage.
    if "OUT" in os.environ:
        output = Path(os.environ["OUT"])
    elif versions is not None:
        output = ROOT / "benchmarks" / versions["app_version"] / "runs" / plan[0]["run_id"] / "raw.csv"
    else:
        output = Path(tempfile.mkdtemp(prefix="prism-motion-unversioned-")) / "raw.csv"
    output.parent.mkdir(parents=True, exist_ok=True)
    trace_output = output.with_name(output.stem + "-samples.csv")
    manifest = Path(args.manifest) if args.manifest else output.with_suffix(".manifest.json")
    if manifest.resolve() in {output.resolve(), trace_output.resolve(),
                              output.with_name(output.stem + "-presents.csv").resolve(), apk.resolve()}:
        raise ValueError("manifest must not overwrite CSV results or the APK")
    write_manifest(manifest, args, apk, selected, plan, versions, asset_hashes)
    print(f"Seed: {args.seed}; repeats: {args.repeats}; trials: {len(plan)}; isolated: {args.isolate_scenarios}", flush=True)
    command(adb + ["install", "-r", str(apk)])
    try:
        rows = run_trials(adb, plan, filenames, args, output, trace_output, versions, asset_hashes)
    finally:
        command(adb + ["shell", "am", "force-stop", PACKAGE])
    print(f"Results: {output}\nSamples: {trace_output}\nPresents: {output.with_name(output.stem + '-presents.csv')}")
    failures = [row for row in rows if row.get("valid") != "true"]
    if failures and not args.allow_invalid:
        raise RuntimeError(f"{len(failures)} invalid scenarios; see valid/error columns (or use --allow-invalid)")
    if failures:
        print(f"Retained {len(failures)} invalid scenarios (--allow-invalid)")


if __name__ == "__main__":
    main()
