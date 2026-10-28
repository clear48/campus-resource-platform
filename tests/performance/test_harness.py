"""验证压测工具的数据隔离门禁，不连接真实服务、不写业务数据。"""

import json
import csv
from pathlib import Path
import re
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import common
import seed
import run as load_runner


class IsolationTests(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(prefix="crp-perf-test-")
        self.root = Path(self.workspace.name)
        self.context = {"apiBase": "http://127.0.0.1:18080/api/v1", "runId": "guard-test",
                        "evidenceDir": str(self.root / "evidence"), "mysqlDatabase": "campus_loadtest_guard",
                        "mysqlHost": "127.0.0.1", "mysqlPort": 13306, "mysqlUser": "root",
                        "mysqlPassword": "synthetic-only", "mysqlExecutable": "mysql",
                        "userPassword": "SyntheticPassword9"}

    def tearDown(self):
        self.workspace.cleanup()

    def context_file(self):
        target = self.root / "context.json"
        target.write_text(json.dumps(self.context), encoding="utf-8")
        return target

    def test_rejects_remote_api(self):
        self.context["apiBase"] = "https://example.com/api/v1"
        with self.assertRaises(ValueError):
            common.load_context(self.context_file())

    def test_rejects_development_database(self):
        self.context["mysqlDatabase"] = "campus_resource_platform"
        with self.assertRaises(ValueError):
            common.load_context(self.context_file())

    def test_rejects_repository_evidence(self):
        self.context["evidenceDir"] = str(Path(__file__).resolve().parents[2] / "tests" / "private-evidence")
        with self.assertRaises(ValueError):
            common.load_context(self.context_file())

    def test_rejects_reparse_or_symlink_path(self):
        context_file = self.context_file()
        metadata = SimpleNamespace(st_mode=stat.S_IFDIR, st_file_attributes=0x400)
        with patch.object(Path, "lstat", return_value=metadata), self.assertRaises(ValueError):
            common.load_context(context_file)

    def test_accepts_local_isolated_target(self):
        actual = common.load_context(self.context_file())
        self.assertEqual("campus_loadtest_guard", actual["mysqlDatabase"])

    def test_nonempty_file_table_rejected_even_when_user_empty(self):
        rows = "\n".join(f"{name}\t{1 if name == 'file_info' else 0}" for name in
                         ("user", "category", "file_info", "user_file_authorization", "resource",
                          "download_delta_sync_item", "favorite", "download_record", "audit_record"))
        with patch.object(seed, "mysql", return_value=rows), self.assertRaises(RuntimeError):
            seed.assert_empty_database(self.context)

    def test_wrong_api_binding_stops_before_register(self):
        categories = []

        def sql_query(_context, sql):
            if "COUNT(*)" in sql:
                return "\n".join(f"table{i}\t0" for i in range(9))
            if "INSERT INTO category" in sql:
                name = re.search(r"VALUES\(0,'([^']+)'", sql).group(1)
                categories.append(name)
                return str(len(categories))
            return "\n".join(f"{i + 1}\t{name}" for i, name in enumerate(categories))

        # SQL 中随机标记与 HTTP 分类不匹配，任何注册/上传操作都不应发生。
        with patch.object(seed, "mysql", side_effect=sql_query), \
                patch.object(seed, "api", return_value=[]) as api_mock, \
                patch.object(seed, "register_and_login") as register_mock, \
                self.assertRaises(RuntimeError):
            seed.seed(self.context, 20, 4)
        register_mock.assert_not_called()
        self.assertEqual(1, api_mock.call_count)
        self.assertEqual("/categories?parentId=0", api_mock.call_args.args[1])

    def test_existing_source_directory_is_not_overwritten(self):
        source_root = Path(self.context["evidenceDir"]) / "synthetic-files"
        source_root.mkdir(parents=True)
        original = source_root / "keep.txt"
        original.write_text("keep", encoding="utf-8")
        with patch.object(seed, "assert_empty_database"), self.assertRaises(FileExistsError):
            seed.seed(self.context, 20, 4)
        self.assertEqual("keep", original.read_text(encoding="utf-8"))

    def test_exclusive_synthetic_file_creation(self):
        source = self.root / "existing.txt"
        source.write_bytes(b"keep")
        with self.assertRaises(FileExistsError):
            seed.create_text(source, "guard-test", 1, 2048)
        self.assertEqual(b"keep", source.read_bytes())

    def test_complete_manifest_cannot_change_sql_target(self):
        evidence = Path(self.context["evidenceDir"])
        common.write_json(evidence / "manifest.json", {
            "status": "COMPLETE", "runId": self.context["runId"], "apiBase": self.context["apiBase"],
            "mysqlTarget": {"host": "127.0.0.1", "port": 13306, "database": "campus_loadtest_other"},
            "resources": [{}] * 20, "accounts": [{}] * 4})
        with patch.object(seed, "verify_category_binding") as binding, self.assertRaises(RuntimeError):
            seed.seed(self.context, 20, 4)
        binding.assert_not_called()

    def test_jtl_summary_counts_assertion_failures_and_labels(self):
        path = self.root / "samples.jtl"
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=("timeStamp", "elapsed", "label", "success", "responseCode"))
            writer.writeheader()
            writer.writerows([
                {"timeStamp": 1000, "elapsed": 10, "label": "search", "success": "true", "responseCode": "200"},
                {"timeStamp": 1100, "elapsed": 30, "label": "detail", "success": "false", "responseCode": "200"},
                {"timeStamp": 1200, "elapsed": 40, "label": "search", "success": "true", "responseCode": "200"}])
        summary = load_runner.summarize(path)
        self.assertEqual(3, summary["overall"]["samples"])
        self.assertEqual(1, summary["overall"]["failures"])
        self.assertEqual(40, summary["overall"]["p95Ms"])
        self.assertEqual(2, summary["labels"]["search"]["samples"])
        self.assertEqual(1, summary["labels"]["detail"]["failures"])
        # 排除最初150ms后只剩最后一条，证明升压样本不会偷偷进入稳态统计。
        self.assertEqual(1, load_runner.summarize(path, .15)["overall"]["samples"])

    def test_zero_sample_jtl_is_not_valid(self):
        path = self.root / "empty.jtl"
        path.write_text("timeStamp,elapsed,label,success,responseCode\n", encoding="utf-8")
        self.assertEqual({"samples": 0, "valid": False}, load_runner.summarize(path)["overall"])


if __name__ == "__main__":
    unittest.main()
