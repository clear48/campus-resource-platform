"""验证压测工具的数据隔离门禁，不连接真实服务、不写业务数据。"""

import json
import csv
import os
import subprocess
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
import generate_plan


GROOVY_SHARED_MAP_REGRESSION = r'''
import groovy.json.JsonSlurper
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput
import java.util.concurrent.*

class FakeVars extends HashMap {
    void putObject(String key, Object value) { put(key, value) }
}
class FakeCtx { int threadNum }
class FakeSampler { String name, domain, protocol, method, path; int port }

def pool = Executors.newFixedThreadPool(100)
def result = [threads:100, oldLazyNulls:0, oldLazyExceptions:0, classicNulls:0, classicExceptions:0, generatedInvalidUrls:0, generatedSamples:0]
try {
    // 放大首次get的填充窗口，轻量复现LazyMap先发布内部Map再逐项填充的真实读取竞态。
    def values = new LinkedHashMap()
    (0..<512).each { values['field'+it] = it }
    values.categories = [[categoryId:1],[categoryId:2]]
    String fixture = JsonOutput.toJson(values)
    [false,true].each { eager ->
        3.times {
            def shared = eager ? new JsonSlurperClassic().parseText(fixture) : new JsonSlurper().parseText(fixture)
            def gate = new CyclicBarrier(100)
            def futures = (0..<100).collect {
                pool.submit({ ->
                    gate.await(10, TimeUnit.SECONDS)
                    try { return [value:shared.categories?.getAt(0)?.categoryId, error:false] }
                    catch(Throwable ignored) { return [value:null,error:true] }
                } as Callable)
            }
            def observations = futures.collect { it.get(15,TimeUnit.SECONDS) }
            int invalid = observations.count { it.value != 1 && !it.error }
            int errors = observations.count { it.error }
            if(eager) { result.classicNulls += invalid; result.classicExceptions += errors }
            else { result.oldLazyNulls += invalid; result.oldLazyExceptions += errors }
        }
    }
    // 直接执行生成器内的真实预处理器；只有FakeSampler存URL，不访问网络。
    Class scriptType = new GroovyClassLoader().parseClass(new File(args[0]).getText('UTF-8'))
    def props = new Properties()
    props.setProperty('manifest',args[1]); props.setProperty('scenario','search'); props.setProperty('searchVariant','category')
    def gate = new CyclicBarrier(100)
    def futures = (0..<100).collect { index ->
        pool.submit({ ->
            gate.await(10,TimeUnit.SECONDS)
            def sampler = new FakeSampler()
            def instance = scriptType.getDeclaredConstructor().newInstance()
            instance.binding = new Binding([props:props,vars:new FakeVars(),ctx:new FakeCtx(threadNum:index),sampler:sampler])
            try { instance.run(); return sampler.path ==~ /.*&categoryId=[1-5]/ }
            catch(Throwable ignored) { return false }
        } as Callable)
    }
    result.generatedInvalidUrls = futures.count { !it.get(15,TimeUnit.SECONDS) }
    result.generatedSamples = futures.size()
    println(JsonOutput.toJson(result))
    assert result.classicNulls == 0
    assert result.classicExceptions == 0
    assert result.generatedInvalidUrls == 0
} finally { pool.shutdownNow() }
'''


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
                {"timeStamp": 1050, "elapsed": 10, "label": "keyword", "success": "true", "responseCode": "200"},
                {"timeStamp": 1100, "elapsed": 30, "label": "detail", "success": "false", "responseCode": "200"},
                {"timeStamp": 1200, "elapsed": 40, "label": "search", "success": "true", "responseCode": "200"}])
        summary = load_runner.summarize(path)
        self.assertEqual(4, summary["overall"]["samples"])
        self.assertEqual(1, summary["overall"]["failures"])
        self.assertEqual(40, summary["overall"]["p95Ms"])
        self.assertEqual(2, summary["labels"]["search"]["samples"])
        self.assertEqual(1, summary["labels"]["detail"]["failures"])
        self.assertAlmostEqual(summary["overall"]["successfulRps"],
                               sum(row["successfulRps"] for row in summary["labels"].values()), delta=.003)
        self.assertEqual(summary["overall"]["windowSeconds"], summary["labels"]["keyword"]["windowSeconds"])
        self.assertLess(summary["labels"]["keyword"]["activeWindowSeconds"], summary["overall"]["windowSeconds"])
        # 排除最初150ms后只剩最后一条，证明升压样本不会偷偷进入稳态统计。
        self.assertEqual(1, load_runner.summarize(path, .15)["overall"]["samples"])

    def test_zero_sample_jtl_is_not_valid(self):
        path = self.root / "empty.jtl"
        path.write_text("timeStamp,elapsed,label,success,responseCode\n", encoding="utf-8")
        self.assertEqual({"samples": 0, "valid": False}, load_runner.summarize(path)["overall"])

    def test_runtime_manifest_rejects_api_run_and_sql_mismatches_before_binding(self):
        valid = {"status": "COMPLETE", "runId": self.context["runId"], "apiBase": self.context["apiBase"],
                 "mysqlTarget": seed.mysql_target(self.context), "categories": []}
        mismatches = ({"runId": "different"}, {"apiBase": "http://127.0.0.1:8080/api/v1"},
                      {"mysqlTarget": None}, {"mysqlTarget": {"database": "campus_loadtest_other"}})
        for change in mismatches:
            with self.subTest(change=change), patch.object(load_runner, "read_json", return_value={**valid, **change}), \
                    patch.object(load_runner, "verify_category_binding") as binding, self.assertRaises(ValueError):
                load_runner.validate_manifest(self.context, self.root)
            binding.assert_not_called()

    def test_runtime_manifest_requires_live_category_binding(self):
        valid = {"status": "COMPLETE", "runId": self.context["runId"], "apiBase": self.context["apiBase"],
                 "mysqlTarget": seed.mysql_target(self.context), "categories": [{"categoryId": 1, "categoryName": "marker"}]}
        with patch.object(load_runner, "read_json", return_value=valid), \
                patch.object(load_runner, "verify_category_binding") as binding:
            load_runner.validate_manifest(self.context, self.root)
        binding.assert_called_once_with(self.context, valid["categories"])

    def test_shared_manifest_is_eager_and_category_urls_are_guarded(self):
        script = generate_plan.PREPROCESSOR
        self.assertIn("new JsonSlurperClassic().parseText(manifestFile.getText('UTF-8'))", script)
        self.assertIn("new JsonSlurperClassic().parseText(contextFile.getText('UTF-8'))", script)
        self.assertIn("longValueExact()", script)
        self.assertIn("Collections.unmodifiableList(categoryIds)", script)
        self.assertIn("categoryId == null", script)
        self.assertNotIn("new JsonSlurper()", script)

    @unittest.skipUnless(os.environ.get("CRP_GROOVY_JMETER_HOME"), "需要显式指定JMeter自带Groovy，默认不启动外部进程")
    def test_jmeter_groovy_100_thread_shared_read_without_http(self):
        jmeter_home = Path(os.environ["CRP_GROOVY_JMETER_HOME"])
        preprocessor = self.root / "preprocessor.groovy"
        preprocessor.write_text(generate_plan.PREPROCESSOR, encoding="utf-8")
        runner = self.root / "regression.groovy"
        runner.write_text(GROOVY_SHARED_MAP_REGRESSION, encoding="utf-8")
        manifest = {"status":"COMPLETE", "apiBase":"http://127.0.0.1:18080/api/v1",
                    "accounts":[{"userId":i+1,"token":"synthetic-only"} for i in range(100)],
                    "approvedIds":list(range(1,101)), "categories":[{"categoryId":i+1} for i in range(5)],
                    "resources":[{"userId":i%100+1,"resourceId":i+1,"status":1} for i in range(1000)]}
        manifest_file = self.root / "manifest.json"
        manifest_file.write_text(json.dumps(manifest), encoding="utf-8")
        command = [os.environ.get("CRP_GROOVY_JAVA", "java"), "-Xms32m", "-Xmx128m", "-cp",
                   str(jmeter_home / "lib" / "*"), "groovy.ui.GroovyMain", str(runner), str(preprocessor), str(manifest_file)]
        completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=60)
        self.assertEqual(0, completed.returncode, "Groovy回归失败：" + completed.stderr[-1200:])
        reports = [json.loads(line) for line in completed.stdout.splitlines() if line.startswith('{')]
        self.assertEqual(1, len(reports))
        report = reports[0]
        self.assertEqual(100, report["generatedSamples"])
        self.assertEqual(0, report["classicNulls"])
        self.assertEqual(0, report["classicExceptions"])
        self.assertEqual(0, report["generatedInvalidUrls"])
        report_path = os.environ.get("CRP_GROOVY_REPORT")
        if report_path:
            Path(report_path).write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(json.dumps({"groovySharedReadRegression":report}), flush=True)


if __name__ == "__main__":
    unittest.main()
