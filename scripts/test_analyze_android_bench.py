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
PRIVATE_TOKENS = ("astc", "rgba", "b1500", "alpha-256", ".ktx", ".mp4", ".prism", "10000", "20000", "4000000",
                  "2500", "private-hardware", "private-protocol", "configuration", "asset", "count",
                  "cpu_percent", "pss_median_mb", "submitted_fps", "version_code", "run_id", "256")
CHART_EXPECTATIONS = {
    "cpu-multiplier.png": ("cpu_cost", "处理器开销", "相对开销（倍）"),
    "memory-multiplier.png": ("memory_cost", "进程内存开销", "相对开销（倍）"),
    "first-output-multiplier.png": ("first_output_cost", "首次输出等待", "相对等待时间（倍）"),
    "file-size-multiplier.png": ("file_cost", "文件体积", "相对体积（倍）"),
    "frame-interval-multiplier.png": ("callback_interval", "成功回调间隔", "相对回调间隔（倍）"),
    "fps-multiplier.png": ("frame_rate", "提交帧率", "相对提交帧率（倍）"),
}


def fixture_rows(versioned=True, historical=False):
    """构造三个私有负载及已知倍数，不把配置暴露到公开数据。 / Creates three private loads with known ratios for privacy assertions."""
    rows = []
    for index, count in enumerate((1, 8, 16)):
        for engine in ("KAP", "VAP"):
            for repeat in (1, 2, 3):
                kap = engine == "KAP"
                suffix = ("ktx" if kap else "mp4") if historical else "prism"
                stem = "alpha-256-astc8x8" if kap else "alpha-256-b1500"
                row = {"asset": f"{stem}.{suffix}", "count": str(count),
                       "valid": "true", "error": "", "cpu_percent": str(10000 * (1 if kap else index + 2)),
                       "pss_median_mb": str(20000 * (1 if kap else (0.8, 0.9, 1.1)[index])),
                       "first_output_ms": str(2500 * (1 if kap else 2)), "asset_bytes": str(4000000 if kap else 200000),
                       "submitted_fps": str(30 if kap else 15), "submit_p95_ms": str(40 if kap else 80),
                       "repeat": str(repeat), "trial": str(repeat), "run_id": f"private-run-{repeat}",
                       "device": "private-hardware", "source_configuration": "private-configuration"}
                if versioned:
                    row.update(app_version="alpha01" if historical else "1.0.0-alpha02",
                               bench_version="alpha01" if historical else "1.0.0-alpha02", version_code="1" if historical else "2")
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
        root_patch = mock.patch.object(ANALYSIS, "ROOT", self.root / "project")
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.input = self.root / "private-trials.csv"

    def public(self, rows=None, size=None):
        """经过实际 CSV 解析构造公开对象。 / Builds the public payload through real CSV ingestion."""
        write_input(self.input, fixture_rows() if rows is None else rows)
        return ANALYSIS.build_public(ANALYSIS.read_trials(self.input), size)

    def test_all_six_metrics_have_explicit_baselines_and_directions(self):
        public = self.public()
        self.assertEqual(public["version"], "1.0.0-alpha02")
        self.assertEqual(public["loads"], ["轻负载", "中负载", "重负载"])
        self.assertEqual(len(public["metrics"]), 6)
        for key, metric in public["metrics"].items():
            self.assertEqual([series["label"] for series in metric["series"]], ["KAP·1.0.0-alpha02", "VAP·1.0.0-alpha02"])
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
            if row["asset"].endswith("-b1500.prism") and row["count"] == "8":
                row.update(valid="false", error="private-native-error", cpu_percent="0")
            if row["asset"].endswith("-b1500.prism") and row["count"] == "16" and row["repeat"] == "3":
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
                values = (100, 200, 300) if row["asset"].endswith("-astc8x8.prism") else (100, 400, 900)
                row["cpu_percent"] = str(values[int(row["repeat"]) - 1])
        public = self.public(rows)
        point = public["metrics"]["cpu_cost"]["series"][1]["points"][0]
        self.assertEqual(point["multiplier"], 2)
        self.assertEqual(point["range"], [1 / 3, 9])
        self.assertIn("不是置信区间", public["method"])

    def test_zero_missing_or_failed_reference_is_never_a_fabricated_one(self):
        rows = fixture_rows()
        for row in rows:
            if row["asset"].endswith("-astc8x8.prism") and row["count"] == "16":
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

    def test_legacy_unversioned_data_never_inherits_a_release_label(self):
        public = self.public(fixture_rows(versioned=False))
        self.assertEqual(public["version"], "未版本化")
        labels = [series["label"] for series in public["metrics"]["cpu_cost"]["series"]]
        self.assertEqual(labels, ["KAP·未版本化", "VAP·未版本化"])
        self.assertNotIn("1.0.0-alpha02", json.dumps(public))
        self.assertNotIn("alpha01", json.dumps(public))

    def test_historical_alpha01_ktx_mp4_remains_readable_without_relabeling(self):
        historical = fixture_rows(historical=True)
        write_input(self.input, historical)
        original = self.input.read_bytes()
        parsed = ANALYSIS.read_trials(self.input)
        public = ANALYSIS.build_public(parsed)
        self.assertEqual(public["version"], "alpha01")
        self.assertEqual([series["label"] for series in public["metrics"]["cpu_cost"]["series"]], ["KAP·alpha01", "VAP·alpha01"])
        self.assertEqual([point["multiplier"] for point in public["metrics"]["cpu_cost"]["series"][1]["points"]], [2, 3, 4])
        self.assertEqual({row["app_version"] for row in parsed}, {"alpha01"})
        self.assertEqual({row["bench_version"] for row in parsed}, {"alpha01"})
        self.assertEqual({row["version_code"] for row in parsed}, {"1"})
        self.assertEqual(self.input.read_bytes(), original)

    def test_new_semver_and_independent_benchmark_versions_are_valid(self):
        for version in ("0.0.0", "1.0.0-alpha01", "1.0.0-alpha99", "2.3.4-beta01", "2.3.4-rc99", "10.20.30"):
            rows = fixture_rows()
            for row in rows:
                row.update(app_version=version, bench_version="3.2.1-rc02", version_code="100")
            with self.subTest(version=version):
                self.assertEqual(self.public(rows)["version"], version)

    def test_noncanonical_new_versions_and_nonpositive_codes_are_rejected(self):
        invalid = ("", "alpha02", "1.0", "1.0.0alpha01", "1.0.0-alpha00", "1.0.0-alpha1", "1.0.0-alpha001",
                   "1.0.0-alpha100", "1.0.0-alpha.1", "1.0.0-ALPHA01", "1.0.0-SNAPSHOT", "01.0.0", "1.00.0", "1.0.00", "1.0.0+build")
        for field in ("app_version", "bench_version"):
            for value in invalid:
                rows = fixture_rows()
                for row in rows:
                    row[field] = value
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    self.public(rows)
        for code in ("0", "-1", "02", "+2", "2.0", "2100000001", "true", "٢"):
            rows = fixture_rows()
            for row in rows:
                row["version_code"] = code
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "正整数"):
                self.public(rows)

    def test_mixed_app_protocol_or_build_versions_are_rejected(self):
        for field, value in (("app_version", "1.0.0-alpha03"), ("bench_version", "another-protocol"), ("version_code", "102"), ("app_version", "")):
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
        extra["asset"] = "alpha-128-astc8x8.prism"
        rows.append(extra)
        with self.assertRaisesRegex(ValueError, "单一"):
            self.public(rows)
        selected = self.public(rows, size=256)
        self.assertEqual(selected["metrics"]["cpu_cost"]["series"][1]["points"][0]["multiplier"], 2)
        with self.assertRaisesRegex(ValueError, "三个"):
            self.public([row for row in fixture_rows() if row["count"] != "16"])

    def test_missing_metric_columns_are_rejected_before_any_partial_output(self):
        output = self.root / "incomplete-public"
        for column in ("first_output_ms", "asset_bytes", "submit_p95_ms", "submitted_fps"):
            with self.subTest(column=column):
                rows = fixture_rows()
                for row in rows:
                    row.pop(column)
                write_input(self.input, rows)
                with mock.patch.object(ANALYSIS, "plot_metric") as plot:
                    with self.assertRaisesRegex(ValueError, "缺少完整指标.*全部六张图"):
                        ANALYSIS.analyze(self.input, output, write_json=True)
                plot.assert_not_called()
                self.assertFalse(output.exists())

    def test_missing_matplotlib_has_actionable_no_install_error(self):
        public = self.public()
        with mock.patch.dict("sys.modules", {"matplotlib": None}):
            with self.assertRaisesRegex(RuntimeError, "matplotlib 不可用；未安装"):
                ANALYSIS.plot_metric(public, "cpu_cost", self.root / "unused.png")


class OutputPathTests(unittest.TestCase):
    """版本归档路径必须匹配输入，旧数据只默认写系统临时目录。 / Version archives must match the input; legacy defaults use system temporary storage only."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="prism-output-path-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.project = self.root / "project"
        self.project.mkdir()
        self.system_temp = self.root / "system-temp"
        self.system_temp.mkdir()
        self.input = self.root / "private.csv"
        write_input(self.input, fixture_rows())
        root_patch = mock.patch.object(ANALYSIS, "ROOT", self.project)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        plot_patch = mock.patch.object(ANALYSIS, "plot_metric")
        self.plot = plot_patch.start()
        self.addCleanup(plot_patch.stop)

    def assert_chart_targets(self, folder):
        """只检查输出边界，不在路径测试中渲染。 / Checks output boundaries without rendering in path tests."""
        self.assertEqual([call.args[2] for call in self.plot.call_args_list],
                         [folder / name for _, name in ANALYSIS.CHARTS])
        self.assertTrue(folder.is_dir())

    def test_default_archive_uses_actual_validated_app_version(self):
        rows = fixture_rows()
        for row in rows:
            row.update(app_version="1.0.0-alpha03", version_code="2")
        write_input(self.input, rows)
        with mock.patch.object(ANALYSIS.tempfile, "mkdtemp") as temporary, mock.patch.object(Path, "home") as home:
            public = ANALYSIS.analyze(self.input)
        self.assertEqual(public["version"], "1.0.0-alpha03")
        self.assert_chart_targets(self.project / "benchmarks" / "1.0.0-alpha03" / "charts")
        self.assertFalse((self.project / "benchmarks" / "1.0.0-alpha02").exists())
        temporary.assert_not_called()
        home.assert_not_called()

    def test_historical_alpha01_defaults_keep_original_archive_name(self):
        write_input(self.input, fixture_rows(historical=True))
        before = self.input.read_bytes()
        public = ANALYSIS.analyze(self.input)
        self.assertEqual(public["version"], "alpha01")
        self.assert_chart_targets(self.project / "benchmarks" / "alpha01" / "charts")
        self.assertFalse((self.project / "benchmarks" / "1.0.0-alpha02").exists())
        self.assertEqual(self.input.read_bytes(), before)

    def test_exact_versioned_project_charts_path_is_allowed_explicitly(self):
        target = self.project / "benchmarks" / "1.0.0-alpha02" / "charts"
        ANALYSIS.analyze(self.input, target, write_json=True)
        self.assert_chart_targets(target)
        self.assertEqual(json.loads((target / "ratios.json").read_text(encoding="utf-8"))["version"], "1.0.0-alpha02")

    def test_root_other_and_mismatched_project_paths_are_rejected(self):
        for target in (self.project, self.project / "other", self.project / "benchmarks",
                       self.project / "benchmarks" / "1.0.0-alpha02", self.project / "benchmarks" / "1.0.0-alpha03" / "charts",
                       self.project / "benchmarks" / "1.0.0-alpha02" / "data", self.project / "benchmarks" / "1.0.0-alpha02" / "charts" / "nested"):
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, "实际应用版本"):
                ANALYSIS.analyze(self.input, target)
        self.plot.assert_not_called()
        self.assertEqual(list(self.project.iterdir()), [])

    def test_legacy_default_uses_system_temp_not_home_or_alpha01(self):
        write_input(self.input, fixture_rows(versioned=False))
        with mock.patch.object(tempfile, "tempdir", str(self.system_temp)), \
                mock.patch.object(ANALYSIS.tempfile, "mkdtemp", wraps=tempfile.mkdtemp) as temporary, \
                mock.patch.object(Path, "home", return_value=self.root / "home") as home:
            public = ANALYSIS.analyze(self.input)
        temporary.assert_called_once_with(prefix="prism-motion-unversioned-")
        home.assert_not_called()
        self.assertEqual(public["version"], "未版本化")
        target = self.plot.call_args_list[0].args[2].parent
        self.assertEqual(target.parent, self.system_temp)
        self.assertTrue(target.name.startswith("prism-motion-unversioned-"))
        self.assert_chart_targets(target)
        self.assertFalse((self.root / "home").exists())
        self.assertEqual(list(self.project.iterdir()), [])

    def test_legacy_cannot_be_archived_in_any_project_version(self):
        write_input(self.input, fixture_rows(versioned=False))
        for label in ("alpha01", "1.0.0-alpha02", "unversioned", "未版本化"):
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, "实际应用版本"):
                ANALYSIS.analyze(self.input, self.project / "benchmarks" / label / "charts")
        self.plot.assert_not_called()
        self.assertEqual(list(self.project.iterdir()), [])

    def test_explicit_external_output_remains_allowed_without_temp_allocation(self):
        for versioned in (True, False):
            write_input(self.input, fixture_rows(versioned=versioned))
            target = self.root / ("external-versioned" if versioned else "external-legacy")
            target.mkdir()
            self.plot.reset_mock()
            with mock.patch.object(ANALYSIS.tempfile, "mkdtemp") as temporary, mock.patch.object(Path, "home") as home:
                ANALYSIS.analyze(self.input, target)
            self.assert_chart_targets(target)
            temporary.assert_not_called()
            home.assert_not_called()

    def test_versioned_archive_keeps_private_file_and_json_guards(self):
        target = self.project / "benchmarks" / "1.0.0-alpha02" / "charts"
        target.mkdir(parents=True)
        private = target / "private.csv"
        private.write_text("private", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "干净"):
            ANALYSIS.analyze(self.input, target)
        self.assertEqual(private.read_text(encoding="utf-8"), "private")
        self.plot.assert_not_called()
        clean = self.root / "external"
        clean.mkdir()
        ratios = clean / "ratios.json"
        ratios.write_text("retained", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "--json"):
            ANALYSIS.analyze(self.input, clean)
        self.assertEqual(ratios.read_text(encoding="utf-8"), "retained")
        self.plot.assert_not_called()

    def test_other_project_aliases_cannot_bypass_the_exact_version_path(self):
        target = self.project / "benchmarks" / "1.0.0-alpha02" / "charts"
        target.mkdir(parents=True)
        wrong_version = self.project / "benchmarks" / "1.0.0-alpha03" / "charts"
        wrong_version.parent.mkdir()
        wrong_version.symlink_to(target, target_is_directory=True)
        external = self.root / "external"
        external.mkdir()
        other = self.project / "other"
        other.symlink_to(external, target_is_directory=True)
        for alias in (wrong_version, other):
            with self.subTest(alias=alias), self.assertRaisesRegex(ValueError, "实际应用版本"):
                ANALYSIS.analyze(self.input, alias)
        self.plot.assert_not_called()

    def test_versioned_symlink_cannot_redirect_to_another_project_path(self):
        target = self.project / "benchmarks" / "1.0.0-alpha02" / "charts"
        target.parent.mkdir(parents=True)
        private = self.project / "private"
        private.mkdir()
        target.symlink_to(private, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "实际应用版本"):
            ANALYSIS.analyze(self.input, target)
        self.plot.assert_not_called()
        self.assertEqual(list(private.iterdir()), [])


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
        self.project = self.root / "project"
        self.project.mkdir()
        root_patch = mock.patch.object(ANALYSIS, "ROOT", self.project)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.input = self.root / "private.csv"
        self.output = self.root / "public"
        self.rows = fixture_rows()
        write_input(self.input, self.rows)

    def test_all_six_clean_chinese_charts_no_markdown_csv_or_json_by_default(self):
        from matplotlib.figure import Figure
        from matplotlib.text import Text
        original_savefig = Figure.savefig
        captured = []
        original_input = self.input.read_bytes()
        for row in self.rows:
            if row["count"] == "16" and row["asset"].endswith("-b1500.prism") and row["repeat"] == "3":
                row.update(valid="false", error="private-native-error")
        write_input(self.input, self.rows)
        original_input = self.input.read_bytes()

        def check_and_save(figure, destination, **kwargs):
            """在真实 PNG 保存前检查可读性和隐私。 / Checks readability and privacy before saving the real PNG."""
            self.assertEqual(len(figure.axes), 1)
            name = Path(destination).name
            key, title, ylabel = CHART_EXPECTATIONS[name]
            axis = figure.axes[0]
            self.assertEqual(axis.get_ylabel(), ylabel)
            self.assertIn(title + "倍数对比", [text.get_text() for text in figure.texts])
            self.assertEqual([tick.get_text() for tick in axis.get_xticklabels()], list(ANALYSIS.LOADS))
            self.assertEqual(list(axis.lines[0].get_ydata()), [1, 1, 1])
            self.assertEqual([text.get_text() for text in figure.legends[0].texts], ["KAP·1.0.0-alpha02", "VAP·1.0.0-alpha02"])
            texts = "\n".join(item.get_text() for item in figure.findobj(match=Text))
            for token in PRIVATE_TOKENS + ("private-native-error",):
                self.assertNotIn(token.lower(), texts.lower())
            self.assertIn("有效 2/3", texts)
            self.assertIn("每档 KAP = 1×", texts)
            direction = "越高越好" if key == "frame_rate" else "越低越好"
            self.assertIn(direction, texts)
            self.assertNotIn("越低越好" if key == "frame_rate" else "越高越好", texts)
            self.assertTrue(any(len(item.get_facecolors()) == 0 for item in axis.collections))
            if key == "file_cost":
                labels = [text for text in axis.texts if text.get_text().startswith("0.05×")]
                self.assertEqual(len(labels), 3)
                figure.canvas.draw()
                for label in labels:
                    self.assertEqual(label.xy[1], 0.05)
                    self.assertGreater(label.get_position()[1], 0)
                    self.assertEqual(label.get_verticalalignment(), "bottom")
                    self.assertGreater(label.get_window_extent().y0, axis.bbox.y0)
            if key == "memory_cost":
                baseline = [text for text in axis.texts if text.get_text() == "1.00×"]
                comparison = [text for text in axis.texts if text.get_text().startswith(("0.80×", "0.90×", "1.10×"))]
                self.assertEqual([text.get_position()[1] > 0 for text in baseline], [True, True, False])
                self.assertEqual([text.get_position()[1] > 0 for text in comparison], [False, False, True])
            captured.append(name)
            original_savefig(figure, destination, **kwargs)

        with mock.patch.object(Figure, "savefig", check_and_save):
            ANALYSIS.analyze(self.input, self.output)
        self.assertEqual(len(captured), 6)
        self.assertEqual(set(captured), set(CHART_EXPECTATIONS))
        self.assertEqual({path.name for path in self.output.iterdir()}, set(captured))
        for png in self.output.iterdir():
            self.assertEqual(png.read_bytes()[:8], b"\x89PNG\r\n\x1a\n")
            self.assertGreater(png.stat().st_size, 30000)
        self.assertEqual(self.input.read_bytes(), original_input)

    def test_missing_endpoint_is_header_warning_not_data_positioned_zero(self):
        from matplotlib.figure import Figure
        original_savefig = Figure.savefig
        captured = []
        for row in self.rows:
            if row["count"] == "16" and row["asset"].endswith("-b1500.prism"):
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
            captured.append(Path(destination).name)
            original_savefig(figure, destination, **kwargs)

        with mock.patch.object(Figure, "savefig", check_and_save):
            ANALYSIS.analyze(self.input, self.output)
        self.assertEqual(len(captured), 6)
        self.assertEqual(set(captured), set(CHART_EXPECTATIONS))

    def test_optional_json_is_ratio_only_and_cli_prints_no_private_configuration(self):
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            result = ANALYSIS.main(["--input", str(self.input), "--output-dir", str(self.output), "--json"])
        self.assertEqual(result, 0)
        self.assertEqual(len(list(self.output.iterdir())), 7)
        self.assertEqual({path.name for path in self.output.iterdir()}, set(CHART_EXPECTATIONS) | {"ratios.json"})
        self.assertEqual(ANALYSIS.PUBLIC_FILES, set(CHART_EXPECTATIONS) | {"ratios.json"})
        self.assertIn("六张相对倍数图", stream.getvalue())
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

    def test_io_errors_do_not_print_private_paths(self):
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            result = ANALYSIS.main(["--input", str(self.root / "private-hardware-secret.csv"), "--output-dir", str(self.output)])
        self.assertEqual(result, 1)
        self.assertNotIn("private-hardware", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
