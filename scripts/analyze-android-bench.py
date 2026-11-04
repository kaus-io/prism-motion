#!/usr/bin/env python3
"""仅离线生成不含原始配置的相对倍数图。 / Produces privacy-bounded relative-multiplier charts entirely offline."""

import argparse
from collections import defaultdict
import csv
import json
import math
import os
from pathlib import Path
import re
import statistics
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
LOADS = ("轻负载", "中负载", "重负载")
PRIVATE_PROFILE = re.compile(r"^alpha-(\d+)-(astc8x8\.ktx|b1500\.mp4)$")
VERSION_PATTERN = re.compile(r"(?:alpha|beta|rc)\d+(?:[._-]\d+)*|v?\d+(?:\.\d+){0,3}(?:[-+][A-Za-z0-9.]+)?")
VERSION_FIELDS = ("app_version", "bench_version", "version_code")
REQUIRED = {"asset", "count", "valid", "error", "cpu_percent", "pss_median_mb"}
METRICS = (
    ("cpu_cost", "cpu_percent", "处理器开销", "lower_is_better"),
    ("memory_cost", "pss_median_mb", "进程内存开销", "lower_is_better"),
    ("first_output_cost", "first_output_ms", "首次输出等待", "lower_is_better"),
    ("file_cost", "asset_bytes", "文件体积", "lower_is_better"),
    ("callback_interval", "submit_p95_ms", "成功回调间隔", "lower_is_better"),
    ("frame_rate", "submitted_fps", "提交帧率", "higher_is_better"),
)
CHARTS = (
    ("cpu_cost", "cpu-multiplier.png"),
    ("memory_cost", "memory-multiplier.png"),
    ("first_output_cost", "first-output-multiplier.png"),
    ("file_cost", "file-size-multiplier.png"),
    ("callback_interval", "frame-interval-multiplier.png"),
    ("frame_rate", "fps-multiplier.png"),
)
AXIS_LABELS = {
    "cpu_cost": "相对开销（倍）",
    "memory_cost": "相对开销（倍）",
    "first_output_cost": "相对等待时间（倍）",
    "file_cost": "相对体积（倍）",
    "callback_interval": "相对回调间隔（倍）",
    "frame_rate": "相对提交帧率（倍）",
}
PUBLIC_FILES = {filename for _, filename in CHARTS} | {"ratios.json"}
COLORS = {"KAP": "#4967E8", "VAP": "#E6535E"}


def number(value, positive=False):
    """缺失、非有限和负哨兵不补零。 / Rejects missing, nonfinite, and negative sentinels without zero imputation."""
    try:
        result = float(value)
    except (ValueError, TypeError):
        return None
    if not math.isfinite(result) or result < 0 or (positive and result == 0):
        return None
    return result


def read_trials(path):
    """仅在内存读取私有数据，禁止将配置复制到公开输出。 / Reads private trials in memory without exporting source configuration."""
    with Path(path).open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        columns = reader.fieldnames or []
        if not REQUIRED.issubset(columns) or len(columns) != len(set(columns)):
            raise ValueError("输入缺少必要测量列或含重复列。")
        rows = []
        for raw in reader:
            if None in raw or any(value is None for value in raw.values()):
                raise ValueError("输入行与表头不一致。")
            row = {key: value.strip() for key, value in raw.items()}
            try:
                count = int(row["count"])
            except ValueError as error:
                raise ValueError("私有负载标识必须为非负整数。") from error
            if count < 0 or not row["asset"]:
                raise ValueError("私有场景标识无效。")
            match = PRIVATE_PROFILE.fullmatch(Path(row["asset"]).name)
            row["_profile"] = (int(match[1]), "KAP" if match[2].endswith(".ktx") else "VAP") if match else None
            row["_count"] = count
            row["_valid"] = row["valid"].lower() in ("true", "1", "yes") and not row["error"]
            rows.append(row)
    if not rows:
        raise ValueError("输入没有试验数据。")
    return rows


def version_label(rows):
    """拒绝版本混合；无版本字段的旧测量只能标记未版本化。 / Rejects mixed identities and never relabels legacy measurements as a new release."""
    identities = {tuple(row.get(field, "") for field in VERSION_FIELDS) for row in rows}
    if len(identities) != 1:
        raise ValueError("混合版本或 bench_version 不一致，必须分开分析。")
    app, bench, code = next(iter(identities))
    if not any((app, bench, code)):
        return "未版本化"
    if not all((app, bench, code)) or not code.isdigit():
        raise ValueError("版本元数据不完整，不能归入已命名版本。")
    if not VERSION_PATTERN.fullmatch(app):
        raise ValueError("应用版本标签不符合公开版本格式。")
    return app


def select_profile(rows, size=None):
    """私下对齐单一逻辑场景的三个负载，不输出原始数量。 / Privately matches one logical profile and three loads without publishing raw counts."""
    candidates = [row for row in rows if row["_profile"] and row["_count"] > 0]
    sizes = {row["_profile"][0] for row in candidates}
    if size is None:
        if len(sizes) != 1:
            raise ValueError("需用 --size 私下选择单一可比场景，不能合并多个场景。")
        size = next(iter(sizes))
    if size not in sizes:
        raise ValueError("选定的私有场景没有测量。")
    selected = [row for row in candidates if row["_profile"][0] == size]
    counts = sorted({row["_count"] for row in selected})
    if len(counts) != len(LOADS):
        raise ValueError("需恰好三个可对齐的非零负载层级。")
    groups = defaultdict(list)
    for row in selected:
        groups[(row["_profile"][1], row["_count"])].append(row)
    for trials in groups.values():
        if len({row["asset"] for row in trials}) > 1:
            raise ValueError("同一可比场景含多个资产标识，不能静默合并。")
    return counts, groups


def private_stats(trials, column):
    """仅用有效 trial 求中位数与范围，原值留在内存。 / Keeps valid-only medians and ranges private in memory."""
    values = [value for row in trials if row["_valid"]
              if (value := number(row.get(column), positive=column == "asset_bytes")) is not None]
    return {"n": len(values), "total": len(trials), "failed": sum(not row["_valid"] for row in trials),
            "median": statistics.median(values) if values else None,
            "min": min(values) if values else None, "max": max(values) if values else None}


def public_point(load, stats, reference, baseline=False):
    """仅释放倍数和有效性，零/缺失基准不能伪造为 1×。 / Releases only ratios and validity; missing or zero references never become a fabricated baseline."""
    denominator = reference["median"]
    available = denominator is not None and denominator > 0 and stats["median"] is not None
    ratio = (1.0 if baseline else stats["median"] / denominator) if available else None
    if ratio is not None and not math.isfinite(ratio):
        available, ratio = False, None
    interval = None
    if available and not baseline and reference["min"] > 0:
        limits = [stats["min"] / reference["max"], stats["max"] / reference["min"]]
        interval = limits if all(math.isfinite(value) for value in limits) else None
    partial = stats["failed"] > 0 or stats["n"] < stats["total"] or reference["failed"] > 0 or reference["n"] < reference["total"]
    return {"load": load, "multiplier": ratio, "range": interval,
            "valid": stats["n"], "total": stats["total"], "failed": stats["failed"],
            "reference_valid": reference["n"], "reference_total": reference["total"],
            "status": "missing" if not available else "partial" if partial else "complete"}


def build_public(rows, size=None):
    """构造白名单化公开数据，不含硬件、配置或绝对测量。 / Builds an allowlisted payload without hardware, configuration, or absolute measurements."""
    version = version_label(rows)
    counts, groups = select_profile(rows, size)
    result = {"version": version, "loads": list(LOADS), "baseline": "每档 KAP = 1×",
              "method": "同一匹配场景：有效样本中位数之比；范围是极值比值包络，不是置信区间。各维度独立，不合成综合性能分。",
              "caveat": "仅当前项目；不是改造前后对照，不代表等画质或等吞吐。部分失败仅据有效样本，缺失不记零。回调间隔不代表物理扫描。",
              "metrics": {}}
    for key, column, label, direction in METRICS:
        if not any(column in row for row in rows):
            continue
        metric = {"label": label, "direction": direction,
                  "direction_label": "越低越好" if direction == "lower_is_better" else "越高越好", "series": []}
        stats = {(engine, count): private_stats(groups.get((engine, count), []), column)
                 for engine in ("KAP", "VAP") for count in counts}
        for engine in ("KAP", "VAP"):
            points = [public_point(load, stats[(engine, count)], stats[("KAP", count)], baseline=engine == "KAP")
                      for load, count in zip(LOADS, counts)]
            metric["series"].append({"label": f"{engine}·{version}", "points": points})
        result["metrics"][key] = metric
    return result


def plot_dependencies():
    """只加载已有 matplotlib，无安装或系统工具链操作。 / Loads only existing matplotlib, without installs or external toolchains."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from matplotlib.font_manager import FontProperties, fontManager
        from matplotlib.ticker import FuncFormatter, MaxNLocator
    except ImportError as error:
        raise RuntimeError("matplotlib 不可用；未安装任何包。") from error
    regular = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
    bold = Path("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc")
    if not regular.is_file():
        paths = [Path(font.fname) for font in fontManager.ttflist
                 if any(name in font.name.lower() for name in ("cjk", "wenquanyi", "simhei", "microsoft yahei"))]
        if not paths:
            raise RuntimeError("没有可用的中文字体；未安装字体。")
        regular = paths[0]
    normal_font = FontProperties(fname=str(regular))
    bold_font = FontProperties(fname=str(bold if bold.is_file() else regular), weight="bold")
    return plt, normal_font, bold_font, FuncFormatter, MaxNLocator


def plot_metric(public, metric_key, destination):
    """生成一张清楚的中文倍数曲线，不泄露私有配置。 / Draws one readable Chinese multiplier chart without exposing private configuration."""
    plt, font, bold, formatter, locator = plot_dependencies()
    metric = public["metrics"][metric_key]
    missing_notices = []
    for engine, series in zip(("KAP", "VAP"), metric["series"]):
        for point in series["points"]:
            if point["multiplier"] is None:
                state = "未完成" if point["valid"] == 0 else "不可比"
                missing_notices.append(f"× {engine} · {point['load']}{state}（有效{point['valid']}/{point['total']}）")
    fig, axis = plt.subplots(figsize=(11.2, 6.8), facecolor="white")
    try:
        fig.subplots_adjust(left=0.115, right=0.94, bottom=0.23, top=min(0.72, 0.77 - 0.029 * len(missing_notices)))
        fig.text(0.09, 0.93, metric["label"] + "倍数对比", fontproperties=bold, fontsize=29, color="#202735")
        fig.text(0.09, 0.862, "每档 KAP = 1×  ·  " + metric["direction_label"], fontproperties=font, fontsize=18, color="#687181")
        maximum = max([1.0] + [point["multiplier"] for series in metric["series"] for point in series["points"] if point["multiplier"] is not None])
        for engine, series in zip(("KAP", "VAP"), metric["series"]):
            values = [point["multiplier"] if point["multiplier"] is not None else math.nan for point in series["points"]]
            axis.plot(range(3), values, label=series["label"], color=COLORS[engine], linewidth=3.4,
                      linestyle="--" if engine == "KAP" else "-", marker="o", markersize=8, zorder=3)
            for index, point in enumerate(series["points"]):
                value = point["multiplier"]
                if value is None:
                    continue
                label = f"{value:.2f}×"
                if point["status"] == "partial":
                    axis.scatter([index], [value], s=175, facecolors="none", edgecolors="#B45025", linewidths=1.6, zorder=5)
                    label += f"\n有效 {point['valid']}/{point['total']}"
                    if engine == "VAP" and point["reference_valid"] < point["reference_total"]:
                        label += f" · 基准 {point['reference_valid']}/{point['reference_total']}"
                comparison = metric["series"][1]["points"][index]["multiplier"]
                above = (comparison is not None and comparison < 1) if engine == "KAP" else value >= 1
                # 小倍数标签留在图内，避免文件体积等指标挤入横轴。 / Keeps small-ratio labels inside the plot instead of colliding with load labels.
                if value < maximum * 0.18:
                    above = True
                axis.annotate(label, (index, value), xytext=(0, 14 if above else -25), textcoords="offset points", ha="center",
                              va="bottom" if above else "top", fontproperties=font, fontsize=13,
                              color=COLORS[engine], fontweight="bold", bbox={"facecolor": "white", "edgecolor": "none", "pad": 1.1})
        axis.set_xlim(-0.2, 2.2)
        axis.set_ylim(0, maximum * 1.32)
        axis.set_xticks(range(3), LOADS, fontproperties=bold, fontsize=17)
        axis.set_ylabel(AXIS_LABELS[metric_key], fontproperties=font, fontsize=15, labelpad=14)
        axis.yaxis.set_major_locator(locator(nbins=5, min_n_ticks=4))
        axis.yaxis.set_major_formatter(formatter(lambda value, _: f"{value:g}×"))
        for tick in axis.get_yticklabels():
            tick.set_fontproperties(font)
            tick.set_fontsize(13)
        axis.grid(axis="y", color="#E9EDF3", linewidth=0.9)
        axis.set_axisbelow(True)
        axis.tick_params(axis="both", length=0, pad=12, colors="#586273")
        for side in ("top", "right", "left"):
            axis.spines[side].set_visible(False)
        axis.spines["bottom"].set_color("#DBE0E8")
        legend_font = font.copy()
        legend_font.set_size(15)
        fig.legend(*axis.get_legend_handles_labels(), loc="upper left", bbox_to_anchor=(0.09, 0.82),
                   ncol=2, frameon=False, prop=legend_font, handlelength=2.8, columnspacing=2)
        if missing_notices:
            fig.text(0.94, 0.79, "\n".join(missing_notices), ha="right", va="top", fontproperties=font,
                     fontsize=12, color="#B45025", bbox={"boxstyle": "round,pad=0.5", "facecolor": "#FFF4F0", "edgecolor": "none"})
        fig.text(0.09, 0.11, "有效样本中位数之比；红圈表示部分有效，缺失不记零。", fontproperties=font, fontsize=12, color="#687181")
        note = "仅当前项目，非综合性能、等吞吐或等画质结论。"
        if metric_key == "memory_cost":
            note += " 进程内存口径，不等于完整图形显存。"
        elif metric_key == "first_output_cost":
            note += " 场景挂载至输出观察，不是物理扫描延迟。"
        elif metric_key == "file_cost":
            note += " 单个资源文件体积，不随负载相乘。"
        elif metric_key == "callback_interval":
            note += " 基于呈现回调间隔 p95，不是屏幕扫描时间。"
        elif metric_key == "frame_rate":
            note += " 提交回调计数，不是物理屏幕扫描帧率。"
        fig.text(0.09, 0.065, note, fontproperties=font, fontsize=11, color="#8B93A0")
        fig.savefig(destination, dpi=180, facecolor="white", metadata={"Software": "Relative multiplier chart"})
    finally:
        plt.close(fig)


def analyze(input_path, output_dir=None, size=None, write_json=False):
    """按实测版本归档公开图，未版本化默认留在系统临时目录。 / Archives public charts by measured version; unversioned defaults use system temporary storage."""
    input_path = Path(input_path).resolve()
    public = build_public(read_trials(input_path), size)
    if any(key not in public["metrics"] for key, _ in CHARTS):
        raise ValueError("输入缺少完整指标，无法生成全部六张图。")
    root = ROOT.resolve()
    version_output = root / "benchmarks" / public["version"] / "charts" if public["version"] != "未版本化" else None
    if output_dir is None:
        output_dir = version_output if version_output is not None else Path(tempfile.mkdtemp(prefix="prism-motion-unversioned-"))
    requested_output = Path(os.path.abspath(output_dir))
    output_dir = requested_output.resolve()
    project_paths = (requested_output, output_dir)
    if (any((path == root or root in path.parents) and path != version_output for path in project_paths)
            or requested_output == version_output and output_dir != version_output):
        raise ValueError("项目内公开输出仅允许 benchmarks/<实际应用版本>/charts；其它目录必须位于项目外。")
    if output_dir.exists() and any(item.name not in PUBLIC_FILES for item in output_dir.iterdir()):
        raise ValueError("输出目录含其它文件；请使用干净的公开目录，避免混入旧报告或私有数据。")
    if (output_dir / "ratios.json").exists() and not write_json:
        raise ValueError("目录已有倍数数据；请加 --json 同步更新，或使用干净目录。")
    targets = [(output_dir / filename).resolve() for filename in PUBLIC_FILES]
    if input_path in targets:
        raise ValueError("公开输出不能覆盖输入。")
    output_dir.mkdir(parents=True, exist_ok=True)
    for key, filename in CHARTS:
        plot_metric(public, key, output_dir / filename)
    if write_json:
        (output_dir / "ratios.json").write_text(json.dumps(public, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    return public


def main(argv=None):
    """不打印输入配置或绝对测量的命令行。 / Provides a CLI that never prints private configuration or absolute measurements."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path, help="private trial CSV")
    parser.add_argument("--output-dir", type=Path, help="clean public directory; default: benchmarks/<measured app_version>/charts; legacy: system temporary directory; other project paths are forbidden")
    parser.add_argument("--size", type=int, help="private logical-profile selector; never exported")
    parser.add_argument("--json", action="store_true", help="also write ratio-only JSON")
    args = parser.parse_args(argv)
    try:
        analyze(args.input, args.output_dir, size=args.size, write_json=args.json)
    except (OSError, ValueError, RuntimeError) as error:
        message = "无法读取输入或写入公开图像。" if isinstance(error, OSError) else str(error)
        print(f"分析未完成：{message}", file=sys.stderr)
        return 1
    print("已生成全部六张相对倍数图。" + (" 已附仅倍数数据。" if args.json else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
