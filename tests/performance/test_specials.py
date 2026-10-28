"""专项工具的纯本地断言测试；不发HTTP、不访问SQL/Redis实例。"""

import io
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import specials


class SpecialsTests(unittest.TestCase):
    def link_fixture(self):
        manifest = {"resources": [{"resourceId": 1, "status": 1, "fileId": 10, "userId": 3,
                                   "md5": "a" * 32, "size": 123}],
                    "uploadedFiles": [{"fileId": 20, "userId": 4, "md5": "b" * 32, "size": 456}]}
        files = [{"fileId": 10, "md5": "a" * 32, "size": 123},
                 {"fileId": 20, "md5": "b" * 32, "size": 456}]
        resources = [{"id": 1, "status": 1, "fileId": 10, "userId": 3}]
        relations = [{"userId": 3, "fileId": 10}, {"userId": 4, "fileId": 20}]
        return manifest, files, resources, relations

    def test_manifest_links_verify_content_identity_and_resource_ownership(self):
        self.assertEqual({1: 1}, specials.verify_manifest_links(*self.link_fixture()))

    def test_manifest_file_must_exist_even_if_authorization_exists(self):
        manifest, files, resources, relations = self.link_fixture()
        for missing_id in (10, 20):
            with self.subTest(fileId=missing_id), self.assertRaises(RuntimeError):
                specials.verify_manifest_links(manifest, [row for row in files if row["fileId"] != missing_id],
                                               resources, relations)

    def test_manifest_resource_and_standalone_upload_digests_and_sizes_must_match(self):
        original = self.link_fixture()
        for index in (0, 1):
            for field, wrong_value in (("md5", "c" * 32), ("size", 999)):
                manifest, files, resources, relations = copy.deepcopy(original)
                files[index][field] = wrong_value
                with self.subTest(fileId=files[index]["fileId"], field=field), self.assertRaises(RuntimeError):
                    specials.verify_manifest_links(manifest, files, resources, relations)

    def test_resource_cannot_switch_file_or_uploader_while_status_stays_same(self):
        original = self.link_fixture()
        for field, wrong_value in (("fileId", 20), ("userId", 4)):
            manifest, files, resources, relations = copy.deepcopy(original)
            resources[0][field] = wrong_value
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                specials.verify_manifest_links(manifest, files, resources, relations)

    def test_manifest_authorization_requirement_is_preserved(self):
        manifest, files, resources, _ = self.link_fixture()
        with self.assertRaises(RuntimeError):
            specials.verify_manifest_links(manifest, files, resources, [])

    def test_download_before_requires_cross_cycle_drain_instead_of_single_snapshot(self):
        manifest = {"accounts": [{"userId": index} for index in range(60)],
                    "resources": [{"status": 1, "resourceId": index + 1} for index in range(3)]}
        events = []
        def drained(_context):
            events.append("drain")
            return [{"empty": True, "elapsedSeconds": 0}, {"empty": True, "elapsedSeconds": 60}]
        def before(_context, _sql):
            events.append("before")
            return [{"id": 1, "count": 0}]
        # 停在任何HTTP或真实等待之前，只验证稳定空门禁先于MySQL初始计数。
        with patch.object(specials, "wait_drained", side_effect=drained) as drain, \
                patch.object(specials, "json_rows", side_effect=before), \
                patch.object(specials, "Downloads", side_effect=RuntimeError("STOP_BEFORE_HTTP")), \
                patch.object(specials, "RedisReader") as reader, \
                self.assertRaisesRegex(RuntimeError, "STOP_BEFORE_HTTP"):
            specials.download({}, manifest, Path("unused"), {"batches": []})
        self.assertEqual(["drain", "before"], events)
        drain.assert_called_once()
        reader.assert_not_called()

    def test_correctness_case_names_are_isolated(self):
        self.assertEqual("correctness", specials.report_mode("correctness", "all"))
        self.assertEqual("correctness-favorite", specials.report_mode("correctness", "favorite"))
        self.assertEqual("correctness-resource", specials.report_mode("correctness", "resource"))
        with tempfile.TemporaryDirectory(prefix="crp-case-test-") as root:
            for case in ("all", "favorite", "resource"):
                mode = specials.report_mode("correctness", case)
                specials.save_progress(Path(root), mode, {"mode": mode, "case": case, "batches": []})
            self.assertEqual({"correctness.special.json", "correctness-favorite.special.json",
                              "correctness-resource.special.json"}, {p.name for p in Path(root).glob("*.json")})

    def test_cli_passes_selected_case_and_defaults_to_all(self):
        with patch.object(specials, "execute") as execute:
            specials.main(["--context", "synthetic.json", "--mode", "correctness", "--case", "resource"])
            execute.assert_called_once_with("synthetic.json", "correctness", "resource", None)
        with patch.object(specials, "execute") as execute:
            specials.main(["--context", "synthetic.json", "--mode", "correctness"])
            execute.assert_called_once_with("synthetic.json", "correctness", "all", None)

    def test_cli_passes_100_start_threads(self):
        with patch.object(specials, "execute") as execute:
            specials.main(["--context", "synthetic.json", "--mode", "upload", "--start-threads", "100"])
            execute.assert_called_once_with("synthetic.json", "upload", "all", 100)

    def test_upload_100_start_adapts_only_large_buffered_files(self):
        matrix = specials.upload_matrix(100)
        self.assertEqual([(100 * 1024, 100, 100), (1024 ** 2, 100, 100), (10 * 1024 ** 2, 10, 20)], matrix)
        self.assertLess(sum(size * count * 2 for size, _, count in matrix), 2 * 1024 ** 3)
        self.assertEqual(12, len(specials.upload_matrix()))

    def test_100_signature_results_are_counted_once_and_ip_rejections_remain_strict(self):
        rows = []
        for index in range(30):
            rows.append({"status": 200, "body": {"code": 0, "data": {
                "resourceId": 7, "downloadRecordId": index + 1, "downloadTicket": "synthetic-only",
                "downloadUrl": f"/api/v1/download-records/{index + 1}/file", "counted": index % 2 == 0}}})
        rows.extend([{"status": 429, "body": {"code": 42901, "message": "当前 IP 下载过于频繁"}} for _ in range(70)])
        accounts = [{"userId": index} for index in range(100)]
        tracker = specials.Downloads({})
        tickets, owners = specials.collect_signature_batch(tracker, accounts, rows, 7)
        self.assertEqual((30, 70), specials.assert_ip_burst(rows, tickets, 100))
        self.assertEqual(30, tracker.post_success)
        self.assertEqual(15, tracker.counted[7])
        self.assertEqual(accounts[:30], owners)
        rows[-1]["body"]["message"] = "下载限流服务暂不可用，请稍后重试"
        with self.assertRaises(RuntimeError):
            specials.assert_ip_burst(rows, tickets, 100)

    def test_case_selection_is_rejected_for_other_modes(self):
        with self.assertRaises(RuntimeError):
            specials.report_mode("upload", "favorite")

    def test_file_check_requires_permission_sensitive_file_id(self):
        miss = {"status": 200, "body": {"code": 0, "data": {"secondUpload": False, "fileId": None}}}
        specials.assert_file_check(miss, False)
        miss["body"]["data"]["fileId"] = 9
        with self.assertRaises(RuntimeError):
            specials.assert_file_check(miss, False)
        hit = {"status": 200, "body": {"code": 0, "data": {"secondUpload": True, "fileId": 9}}}
        specials.assert_file_check(hit, True, 9)

    def test_visibility_and_cold_cache_probe_uses_one_explicit_detail_key(self):
        resources = [{"resourceId": 1, "status": 1}, {"resourceId": 2, "status": 0},
                     {"resourceId": 3, "status": 2}, {"resourceId": 4, "status": 3}]
        good = {"status": 200, "body": {"code": 0, "data": {"resourceId": 1, "status": 1}},
                "elapsedMs": 10, "startedPerf": 1, "endedPerf": 1.01}
        hidden = {"status": 400, "body": {"code": 40901}}
        missing = {"status": 404, "body": {"code": 40401}}
        unauthorized = {"status": 401, "body": {"code": 40101}}
        reader = unittest.mock.Mock()
        reader.command.side_effect = [1, 0, 1900]
        def concurrent(_count, _operation, callback):
            callback([good] * _count)
            return [good] * _count, .01
        with tempfile.TemporaryDirectory(prefix="crp-cache-test-") as root, \
                patch.object(specials, "timed_request", side_effect=[good, hidden, hidden, hidden, missing, unauthorized]), \
                patch.object(specials, "mysql", return_value="10004"), \
                patch.object(specials, "RedisReader", return_value=reader), \
                patch.object(specials, "parallel", side_effect=concurrent):
            report = {"batches": []}
            specials.public_visibility_and_cache({}, {"resources": resources}, Path(root), report, start_threads=100)
        self.assertEqual(100, report["coldCache"]["success"])
        self.assertEqual(100, report["coldCache"]["threads"])
        self.assertFalse(report["coldCache"]["sqlQueriesMeasured"])
        self.assertTrue(report["visibility"]["noToken401"])
        self.assertEqual([unittest.mock.call("DEL", "crp:cache:resource:detail:1"),
                          unittest.mock.call("EXISTS", "crp:cache:resource:detail:1"),
                          unittest.mock.call("TTL", "crp:cache:resource:detail:1")], reader.command.call_args_list)
        reader.close.assert_called_once()

    def test_parallel_barrier_collects_all_results(self):
        def operation(index):
            return {"startedPerf": 1 + index / 100, "endedPerf": 2 + index / 100, "index": index}
        rows, duration = specials.parallel(5, operation)
        self.assertEqual([0, 1, 2, 3, 4], [row["index"] for row in rows])
        self.assertAlmostEqual(1.04, duration)

    def test_parallel_uncertain_request_is_not_retried(self):
        calls = []
        def operation(index):
            calls.append(index)
            if index == 1:
                raise TimeoutError()
            return {"startedPerf": 1, "endedPerf": 2}
        with self.assertRaises(RuntimeError):
            specials.parallel(3, operation)
        self.assertEqual([0, 1, 2], sorted(calls))

    def test_parallel_preserves_counts_before_uncertain_assertion(self):
        captured = []
        def operation(index):
            if index == 0:
                raise TimeoutError()
            return {"status": 400, "body": {"code": 40901}, "startedPerf": 1, "endedPerf": 2}
        with self.assertRaises(RuntimeError):
            specials.parallel(2, operation, lambda rows: captured.extend(rows))
        self.assertEqual(2, len(captured))
        self.assertEqual(1, sum(bool(row.get("uncertain")) for row in captured))

    def test_last_attempt_records_codes_without_response_or_token(self):
        with tempfile.TemporaryDirectory(prefix="crp-evidence-test-") as root:
            report = {"batches": []}
            rows = [{"status": 200, "body": {"code": 0, "data": {
                "duplicateIgnored": False, "accessToken": "PRIVATE-TOKEN-DO-NOT-SAVE"}}},
                    {"status": 400, "body": {"code": 40901, "message": "PRIVATE-RAW-RESPONSE"}}]
            specials.record_attempt(Path(root), "correctness", report, "favorite.add", rows, 2, 1)
            saved = json.loads((Path(root) / "correctness.special.json").read_text(encoding="utf-8"))
            self.assertEqual({"200": 1, "400": 1}, saved["lastAttempt"]["httpStatusCounts"])
            self.assertEqual({"0": 1, "40901": 1}, saved["lastAttempt"]["businessCodeCounts"])
            self.assertEqual({"false": 1, "missing": 1}, saved["lastAttempt"]["duplicateIgnoredCounts"])
            self.assertNotIn("PRIVATE", json.dumps(saved))

    def test_failure_snapshot_retains_database_count_mismatch(self):
        report = {"favoriteTarget": {"userId": 1, "resourceId": 2}}
        with patch.object(specials, "json_rows", return_value=[{
                "activeForUserResource": 1, "resourceFavoriteCount": 0, "activeForResource": 1}]), \
                patch.object(specials, "favorite_discrepancies", return_value=1):
            specials.capture_failure_database_state({}, report)
        self.assertEqual(1, report["failureDatabaseState"]["favoriteAggregateMismatches"])
        self.assertEqual(0, report["failureDatabaseState"]["resourceFavoriteCount"])

    def test_counted_true_only_accumulates_counted_signatures(self):
        tracker = specials.Downloads({"apiBase": "http://127.0.0.1:18080/api/v1"})
        response = {"status": 200, "body": {"code": 0, "data": {
            "resourceId": 7, "downloadRecordId": 9, "downloadTicket": "synthetic-ticket",
            "downloadUrl": "/api/v1/download-records/9/file", "counted": True}}}
        with patch.object(specials, "timed_request", return_value=response):
            tracker.sign({"token": "synthetic-only"}, 7)
            response["body"]["data"]["counted"] = False
            tracker.sign({"token": "synthetic-only"}, 7)
        self.assertEqual(2, tracker.post_success)
        self.assertEqual(1, tracker.counted[7])

    def test_signed_url_must_match_record_and_api_path(self):
        tracker = specials.Downloads({"apiBase": "http://127.0.0.1:18080/api/v1"})
        response = {"status": 200, "body": {"code": 0, "data": {
            "resourceId": 7, "downloadRecordId": 9, "downloadTicket": "synthetic-only",
            "downloadUrl": "https://example.com/other", "counted": True}}}
        with patch.object(specials, "timed_request", return_value=response), self.assertRaises(RuntimeError):
            tracker.sign({"token": "synthetic-only"}, 7)

    def test_binary_file_requires_attachment_length_and_hash(self):
        import hashlib
        content = b"synthetic content"
        resource = {"size": len(content), "md5": hashlib.md5(content).hexdigest()}
        result = {"status": 200, "headers": {"Content-Type": "application/octet-stream",
                  "Content-Disposition": "attachment", "Content-Length": str(len(content))}, "body": content}
        tracker = specials.Downloads({})
        tracker.validate_file(result, resource)
        self.assertEqual(1, tracker.get_complete)
        result["body"] = b"corrupted"
        with self.assertRaises(RuntimeError):
            tracker.validate_file(result, resource)

    def test_redis_resp_values_and_errors(self):
        reader = specials.RedisReader.__new__(specials.RedisReader)
        reader.stream = io.BytesIO(b"*3\r\n:2\r\n$3\r\nabc\r\n$-1\r\n")
        self.assertEqual([2, "abc", None], reader.read())
        reader.stream = io.BytesIO(b"-ERR synthetic\r\n")
        with self.assertRaises(RuntimeError):
            reader.read()

    def test_disk_guard_includes_pending_upload_bytes(self):
        with patch.object(specials.shutil, "disk_usage", return_value=type("Usage", (), {"free": 10 * 1024 ** 3})()):
            with self.assertRaises(RuntimeError):
                specials.require_space(Path("."), 1)

    def test_source_content_is_unique_and_exclusively_created(self):
        with tempfile.TemporaryDirectory(prefix="crp-special-test-") as root:
            first, first_md5 = specials.source_file(Path(root), "test", 2048)
            second, second_md5 = specials.source_file(Path(root), "test", 2048)
            self.assertNotEqual(first, second)
            self.assertNotEqual(first_md5, second_md5)
            self.assertEqual(2048, first.stat().st_size)
            self.assertEqual(first_md5, specials.digest_file(first))

    def test_batch_statistics_mark_low_samples(self):
        rows = [{"status": 200, "body": {"code": 0}, "elapsedMs": n} for n in (10, 20)]
        metrics = specials.batch_metrics(rows, 2, [1024, 1024])
        self.assertTrue(metrics["lowSamplePercentiles"])
        self.assertEqual(1, metrics["successfulRps"])
        self.assertEqual(2048, metrics["bytes"])


if __name__ == "__main__":
    unittest.main()
