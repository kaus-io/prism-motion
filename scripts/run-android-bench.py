#!/usr/bin/env python3
"""运行当前 Android bench，校验完整性并保存结果。 / Runs this project's Android bench and validates complete output."""
import argparse
import csv
import io
import os
from pathlib import Path
import selectors
import shlex
import subprocess
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.zxhhyj.prism.motion.bench.android"


def command(argv, **kwargs):
    """任一步失败即报错，不吞掉 adb/构建错误。 / Propagates every adb/build failure."""
    return subprocess.run(argv, check=True, text=True, **kwargs)


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="explicit connected ADB device serial")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--counts", default="1,8,16")
    parser.add_argument("--measure-ms", type=int, default=2500)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    counts = [int(value) for value in args.counts.split(",")]
    if not counts or any(value < 0 or value > 64 for value in counts) or len(counts) != len(set(counts)):
        parser.error("counts must be unique integers from 0 to 64")
    adb = ["adb", "-s", args.serial]
    command(adb + ["get-state"], stdout=subprocess.DEVNULL)
    if not args.skip_build:
        build = [str(ROOT / "gradlew"), "-p", str(ROOT), ":bench-android:assembleDebug", "--console=plain"]
        if os.environ.get("DEMO_ASSETS"):
            build += ["-PdemoAssets=" + os.environ["DEMO_ASSETS"]]
        command(build)
    apk = ROOT / "bench-android/build/outputs/apk/debug/bench-android-debug.apk"
    with zipfile.ZipFile(apk) as archive:
        filenames = {Path(info.filename).stem: Path(info.filename).name for info in archive.infolist()
                     if info.filename.startswith("assets/") and info.filename.endswith((".ktx", ".mp4"))}
        assets = sorted(filenames)
        compressed_ktx = [info.filename for info in archive.infolist()
                          if info.filename.endswith((".ktx", ".ktx2")) and info.compress_type != zipfile.ZIP_STORED]
        if compressed_ktx:
            raise RuntimeError(f"KAP assets must use noCompress: {compressed_ktx}")
    selected = shlex.split(os.environ.get("ASSETS", "")) or assets
    missing = set(selected) - set(assets)
    if missing or not selected:
        raise RuntimeError(f"No matching APK assets; missing={sorted(missing)}")
    command(adb + ["install", "-r", str(apk)])
    output = Path(os.environ.get("OUT", ROOT / "bench-android/build/reports/bench.csv"))
    output.parent.mkdir(parents=True, exist_ok=True)
    trace_output = output.with_name(output.stem + "-samples.csv")
    rows, traces, columns, trace_columns = [], [], None, None
    failures = []
    for asset in selected:
        command(adb + ["shell", "am", "force-stop", PACKAGE])
        run_id = uuid.uuid4().hex
        launch = adb + ["shell", "am", "start", "-W", "-n", PACKAGE + "/.BenchActivity", "--es", "only", asset,
                        "--es", "counts", args.counts, "--el", "measure_ms", str(args.measure_ms),
                        "--es", "run_id", run_id]
        wait_error = None
        try:
            wait_done(adb, run_id, launch, args.timeout)
        except (RuntimeError, TimeoutError) as error:
            wait_error = str(error)
            print(wait_error, flush=True)
        remote = f"/sdcard/Android/data/{PACKAGE}/files/"
        raw = command(adb + ["exec-out", "cat", remote + "bench.csv"], stdout=subprocess.PIPE).stdout
        reader = csv.DictReader(io.StringIO(raw))
        current = list(reader)
        if wait_error:
            present_counts = {int(row["count"]) for row in current}
            for count in counts:
                if count not in present_counts:
                    current.append(dict.fromkeys(reader.fieldnames, "") | {
                        "asset": filenames[asset], "count": str(count), "valid": "false", "error": wait_error,
                    })
        actual = [(Path(row["asset"]).stem, int(row["count"])) for row in current]
        if sorted(actual) != sorted((asset, count) for count in counts):
            raise RuntimeError(f"Incomplete/duplicate scenarios for {asset}: {actual}")
        columns = columns or reader.fieldnames
        rows.extend(current)
        failures.extend(row for row in current if row["valid"] != "true")
        raw_trace = command(adb + ["exec-out", "cat", remote + "samples.csv"], stdout=subprocess.PIPE).stdout
        trace_reader = csv.DictReader(io.StringIO(raw_trace))
        trace_columns = trace_columns or trace_reader.fieldnames
        traces.extend(trace_reader)
        # 每次成功后落盘，意外中断也不丢已完成资产。 / Persists each completed asset against interruption.
        for path, fields, data in ((output, columns, rows), (trace_output, trace_columns, traces)):
            with path.open("w", newline="") as handle:
                writer = csv.DictWriter(handle, fieldnames=fields)
                writer.writeheader()
                writer.writerows(data)
        print(f"Completed {asset}: {len(current)} rows", flush=True)
        if wait_error:
            command(adb + ["shell", "am", "force-stop", PACKAGE])
            raise RuntimeError(f"{wait_error}; partial results saved to {output}")
    command(adb + ["shell", "am", "force-stop", PACKAGE])
    print(f"Results: {output}\nSamples: {trace_output}")
    if failures:
        raise RuntimeError(f"{len(failures)} invalid scenarios; see valid/error columns")


if __name__ == "__main__":
    main()
