#!/usr/bin/env python3
"""只用临时合成数据测试倍数图，不访问设备。 / Tests multiplier charts using temporary synthetic data, without device access."""

import contextlib
import csv
import importlib.util
import io
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("analyze-android-bench.py")
SPEC = importlib.util.spec_from_file_location("analyze_android_bench", SCRIPT)
ANALYSIS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ANALYSIS)
COLUMNS = ["asset", "count", "valid", "error", "cpu_percent", "pss_median_mb", "first_output_ms",
           "asset_bytes", "submitted_fps", "submit_p95_ms", "app_version", "bench_version", "version_code",
           "repeat", "trial", "run_id", "device", "source_configuration"]
PRIVATE_TOKENS = ("astc", "rgba", "b1500", "alpha-256", ".ktx", ".mp4", "10000", "20000", "4000000",
                  "2500", "private-hardware", "private-protocol", "configuration", "asset", "count",
                  "cpu_percent", "pss_median_mb", "submitted_fps", "version_code", "run_id", "256")


def fixture_rows(versioned=True):
    """构造三个私有负载及已知倍数，不把配置暴露到公开数据。 / Creates three private loads with known ratios for privacy assertions."""
    rows = []
    for index, count in enumerate((1, 8, 16)):
        for engine in ("KAP", "VAP"):
            for repeat in (1, 2, 3):
                kap = engine == "KAP"
                row = {"asset": "alpha-256-astc8x8.ktx" if kap else "alpha-256-b1500.mp4", "count": str(count),
                       "valid": "true", "error": "", "cpu_percent": str(10000 * (1 if kap else index + 2)),
                       "pss_median_mb": str(20000 * (1 if kap else (0.8, 0.9, 1.1)[index])),
                       "first_output_ms": str(2500 * (1 if kap else 2)), "asset_bytes": str(4000000 if kap else 200000),
                       "submitted_fps": str(30 if kap else 15), "submit_p95_ms": str(40 if kap else 80),
                       "repeat": str(repeat), "trial": str(repeat), "run_id": f"private-run-{repeat}",
                       "device": "private-hardware", "source_configuration": "private-configuration"}
                if versioned:
                    row.update(app_version="alpha01", bench_version="private-protocol-v1", version_code="101")
                rows.append(row)
    baseline = dict(rows[0])
    baseline.update(count="0", cpu_percent="99999999", pss_median_mb="99999999")
    rows.append(baseline)
    return rows


def write_input(path, rows):
    """仅写临时私有 CSV。 / Writes private fixture CSV only to a temporary directory."""
    columns = [column for column in COLUMNS if any(column in row for row in rows)]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


class MultiplierTests(unittest.TestCase):
    """覆盖倍数、方向、版本和失败口径。 / Covers multiplier math, directions, version identity, and invalid trials."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="prism-relative-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.input = self.root / "private-trials.csv"

    def public(self, rows=None, size=None):
        """经过实际 CSV 解析构造公开对象。 / Builds the public payload through real CSV ingestion."""
        write_input(self.input, fixture_rows() if rows is None else rows)
        return ANALYSIS.build_public(ANALYSIS.read_trials(self.input), size)

    def test_all_six_metrics_have_explicit_baselines_and_directions(self):
        public = self.public()
        self.assertEqual(public["version"], "alpha01")
        self.assertEqual(public["loads"], ["轻负载", "中负载", "重负载"])
        self.assertEqual(len(public["metrics"]), 6)
        for key, metric in public["metrics"].items():
            self.assertEqual([series["label"] for series in metric["series"]], ["KAP·alpha01", "VAP·alpha01"])
            self.assertEqual([point["multiplier"] for point in metric["series"][0]["points"]], [1, 1, 1])
            direction = "higher_is_better" if key == "frame_rate" else "lower_is_better"
            self.assertEqual(metric["direction"], direction)
        expected = {"cpu_cost": [2, 3, 4], "memory_cost": [0.8, 0.9, 1.1], "first_output_cost": [2, 2, 2],
                    "file_cost": [0.05, 0.05, 0.05], "callback_interval": [2, 2, 2], "frame_rate": [0.5, 0.5, 0.5]}
        for key, values in expected.items():
            self.assertEqual([point["multiplier"] for point in public["metrics"][key]["series"][1]["points"]], values)
        self.assertNotIn("score", public)

    def test_public_payload_contains_no_configuration_or_absolute_values(self):
        text = json.dumps(self.public(), ensure_ascii=False).lower()
        for token in PRIVATE_TOKENS:
            self.assertNotIn(token.lower(), text)
        self.assertIn("每档 KAP = 1×", self.public()["baseline"])

    def test_partial_failures_keep_valid_n_and_all_failures_are_missing(self):
        rows = fixture_rows()
        for row in rows:
            if row["asset"].endswith(".mp4") and row["count"] == "8":
                row.update(valid="false", error="private-native-error", cpu_percent="0")
            if row["asset"].endswith(".mp4") and row["count"] == "16" and row["repeat"] == "3":
                row.update(valid="false", error="private-native-error", cpu_percent="99999999")
        points = self.public(rows)["metrics"]["cpu_cost"]["series"][1]["points"]
        self.assertIsNone(points[1]["multiplier"])
        self.assertEqual((points[1]["valid"], points[1]["total"], points[1]["failed"]), (0, 3, 3))
        self.assertEqual(points[1]["status"], "missing")
        self.assertEqual((points[2]["multiplier"], points[2]["valid"], points[2]["total"]), (4, 2, 3))
        self.assertEqual(points[2]["status"], "partial")
        self.assertNotIn("private-native-error", json.dumps(self.public(rows)))

    def test_median_ratios_and_extreme_envelope_are_not_confidence_intervals(self):
        rows = fixture_rows()
        for row in rows:
            if row["count"] == "1":
                values = (100, 200, 300) if row["asset"].endswith(".ktx") else (100, 400, 900)
                row["cpu_percent"] = str(values[int(row["repeat"]) - 1])
        public = self.public(rows)
        point = public["metrics"]["cpu_cost"]["series"][1]["points"][0]
        self.assertEqual(point["multiplier"], 2)
        self.assertEqual(point["range"], [1 / 3, 9])
        self.assertIn("不是置信区间", public["method"])

    def test_zero_missing_or_failed_reference_is_never_a_fabricated_one(self):
        rows = fixture_rows()
        for row in rows:
            if row["asset"].endswith(".ktx") and row["count"] == "16":
                row["cpu_percent"] = "0"
        metric = self.public(rows)["metrics"]["cpu_cost"]
        self.assertIsNone(metric["series"][0]["points"][2]["multiplier"])
        self.assertIsNone(metric["series"][1]["points"][2]["multiplier"])
        self.assertIsNone(ANALYSIS.number("nan"))
        self.assertIsNone(ANALYSIS.number("inf"))
        self.assertIsNone(ANALYSIS.number(-1))

    def test_ratio_overflow_is_missing_not_nonfinite_public_data(self):
        numerator = {"n": 1, "total": 1, "failed": 0, "median": 1e308, "min": 1e308, "max": 1e308}
        reference = {"n": 1, "total": 1, "failed": 0, "median": 1e-308, "min": 1e-308, "max": 1e-308}
        point = ANALYSIS.public_point("轻负载", numerator, reference)
        self.assertIsNone(point["multiplier"])
        self.assertEqual(point["status"], "missing")
        json.dumps(point, allow_nan=False)

    def test_legacy_is_unversioned_and_never_alpha01(self):
        public = self.public(fixture_rows(versioned=False))
        self.assertEqual(public["version"], "未版本化")
        labels = [series["label"] for series in public["metrics"]["cpu_cost"]["series"]]
        self.assertEqual(labels, ["KAP·未版本化", "VAP·未版本化"])
        self.assertNotIn("alpha01", json.dumps(public))

    def test_mixed_app_protocol_or_build_versions_are_rejected(self):
        for field, value in (("app_version", "alpha02"), ("bench_version", "another-protocol"), ("version_code", "102"), ("app_version", "")):
            with self.subTest(field=field):
                rows = fixture_rows()
                rows[-1][field] = value
                with self.assertRaisesRegex(ValueError, "混合版本|bench_version"):
                    self.public(rows)
        rows = fixture_rows()
        for row in rows:
            row["bench_version"] = ""
        with self.assertRaisesRegex(ValueError, "不完整"):
            self.public(rows)

    def test_private_profile_and_three_load_alignment_are_not_silently_merged(self):
        rows = fixture_rows()
        extra = dict(rows[0])
        extra["asset"] = "alpha-128-astc8x8.ktx"
        rows.append(extra)
        with self.assertRaisesRegex(ValueError, "单一"):
            self.public(rows)
        selected = self.public(rows, size=256)
        self.assertEqual(selected["metrics"]["cpu_cost"]["series"][1]["points"][0]["multiplier"], 2)
        with self.assertRaisesRegex(ValueError, "三个"):
            self.public([row for row in fixture_rows() if row["count"] != "16"])

    def test_missing_matplotlib_has_actionable_no_install_error(self):
        public = self.public()
        with mock.patch.dict("sys.modules", {"matplotlib": None}):
            with self.assertRaisesRegex(RuntimeError, "matplotlib 不可用；未安装"):
                ANALYSIS.plot_metric(public, "cpu_cost", self.root / "unused.png")


class PublicChartTests(unittest.TestCase):
    """验证中文 PNG、白名单输出及公开隐私。 / Verifies Chinese PNGs, allowlisted outputs, and public privacy."""

    def setUp(self):
        try:
            import matplotlib
            matplotlib.use("Agg")
            import matplotlib.pyplot
        except ImportError:
            self.skipTest("existing matplotlib unavailable; no install attempted")
        self.temp = tempfile.TemporaryDirectory(prefix="prism-public-chart-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.input = self.root / "private.csv"
        self.output = self.root / "public"
        self.rows = fixture_rows()
        write_input(self.input, self.rows)

    def test_two_clean_chinese_charts_no_markdown_csv_or_json_by_default(self):
        from matplotlib.figure import Figure
        from matplotlib.text import Text
        original_savefig = Figure.savefig
        captured = []
        original_input = self.input.read_bytes()
        for row in self.rows:
            if row["count"] == "16" and row["asset"].endswith(".mp4") and row["repeat"] == "3":
                row.update(valid="false", error="private-native-error")
        write_input(self.input, self.rows)
        original_input = self.input.read_bytes()

        def check_and_save(figure, destination, **kwargs):
            """在真实 PNG 保存前检查可读性和隐私。 / Checks readability and privacy before saving the real PNG."""
            self.assertEqual(len(figure.axes), 1)
            axis = figure.axes[0]
            self.assertEqual([tick.get_text() for tick in axis.get_xticklabels()], list(ANALYSIS.LOADS))
            self.assertEqual(list(axis.lines[0].get_ydata()), [1, 1, 1])
            self.assertEqual([text.get_text() for text in figure.legends[0].texts], ["KAP·alpha01", "VAP·alpha01"])
            texts = "\n".join(item.get_text() for item in figure.findobj(match=Text))
            for token in PRIVATE_TOKENS + ("private-native-error",):
                self.assertNotIn(token.lower(), texts.lower())
            self.assertIn("有效 2/3", texts)
            self.assertIn("每档 KAP = 1×", texts)
            self.assertIn("越低越好", texts)
            self.assertIn("相对开销（倍）", texts)
            self.assertTrue(any(len(item.get_facecolors()) == 0 for item in axis.collections))
            captured.append(Path(destination).name)
            original_savefig(figure, destination, **kwargs)

        with mock.patch.object(Figure, "savefig", check_and_save):
            ANALYSIS.analyze(self.input, self.output)
        self.assertEqual(set(captured), {"cpu-multiplier.png", "memory-multiplier.png"})
        self.assertEqual({path.name for path in self.output.iterdir()}, set(captured))
        for png in self.output.iterdir():
            self.assertEqual(png.read_bytes()[:8], b"\x89PNG\r\n\x1a\n")
            self.assertGreater(png.stat().st_size, 30000)
        self.assertEqual(self.input.read_bytes(), original_input)

    def test_missing_endpoint_is_header_warning_not_data_positioned_zero(self):
        from matplotlib.figure import Figure
        original_savefig = Figure.savefig
        for row in self.rows:
            if row["count"] == "16" and row["asset"].endswith(".mp4"):
                row.update(valid="false", error="private-native-error", cpu_percent="0", pss_median_mb="0")
        write_input(self.input, self.rows)

        def check_and_save(figure, destination, **kwargs):
            """缺失只在图外警告，折线停在真实端点。 / Missing results use a header badge while the curve ends at its last real point."""
            axis = figure.axes[0]
            self.assertTrue(math.isnan(axis.lines[1].get_ydata()[-1]))
            self.assertEqual(len(axis.collections), 0)
            self.assertFalse(any("未完成" in text.get_text() or "缺失" in text.get_text() for text in axis.texts))
            badges = [text for text in figure.texts if text.get_text().startswith("× VAP")]
            self.assertEqual(len(badges), 1)
            self.assertIn("重负载未完成（有效0/3）", badges[0].get_text())
            self.assertGreater(badges[0].get_position()[1], axis.get_position().y1)
            original_savefig(figure, destination, **kwargs)

        with mock.patch.object(Figure, "savefig", check_and_save):
            ANALYSIS.analyze(self.input, self.output)

    def test_optional_json_is_ratio_only_and_cli_prints_no_private_configuration(self):
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            result = ANALYSIS.main(["--input", str(self.input), "--output-dir", str(self.output), "--json"])
        self.assertEqual(result, 0)
        self.assertEqual({path.name for path in self.output.iterdir()}, ANALYSIS.PUBLIC_FILES)
        public = json.loads((self.output / "ratios.json").read_text(encoding="utf-8"))
        text = json.dumps(public, ensure_ascii=False) + stream.getvalue()
        for token in PRIVATE_TOKENS:
            self.assertNotIn(token.lower(), text.lower())
        self.assertEqual(public["metrics"]["frame_rate"]["direction"], "higher_is_better")
        self.assertEqual(len(public["metrics"]), 6)

    def test_project_outputs_and_mixed_private_directory_are_refused(self):
        with self.assertRaisesRegex(ValueError, "项目外"):
            ANALYSIS.analyze(self.input, ANALYSIS.ROOT / "forbidden-public-output")
        self.output.mkdir()
        (self.output / "private-old.csv").write_text("private", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "干净"):
            ANALYSIS.analyze(self.input, self.output)
        self.assertEqual(list(self.output.iterdir()), [self.output / "private-old.csv"])

    def test_default_output_is_outside_project_and_legacy_folder_is_not_alpha01(self):
        write_input(self.input, fixture_rows(versioned=False))
        with mock.patch.object(ANALYSIS.Path, "home", return_value=self.root):
            public = ANALYSIS.analyze(self.input)
        target = self.root / "prism-motion-results" / "unversioned"
        self.assertEqual(public["version"], "未版本化")
        self.assertEqual({path.name for path in target.iterdir()}, {name for _, name in ANALYSIS.CHARTS})
        self.assertFalse((self.root / "prism-motion-results" / "alpha01").exists())

    def test_io_errors_do_not_print_private_paths(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = ANALYSIS.main(["--input", str(self.root / "private-hardware-secret.csv"), "--output-dir", str(self.output)])
        self.assertEqual(result, 1)
        self.assertNotIn("private-hardware", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
