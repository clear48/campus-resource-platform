"""本地并发正确性、上传短批、下载规则及停压对账；不修改应用业务规则。"""

import argparse
import collections
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import math
from pathlib import Path
import shutil
import socket
import stat
import sys
import threading
import time
import uuid

from common import api, load_context, mysql, read_json, request, write_json
from run import percentile
from seed import create_text, verify_category_binding


def expect(condition, message):
    if not condition:
        raise RuntimeError(message)


def json_rows(context, sql):
    return [json.loads(line) for line in mysql(context, sql).splitlines() if line]


def digest_file(path):
    """流式核对大文件，避免将整个上传目录载入内存。"""
    digest = hashlib.md5()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def timed_request(context, path, method="GET", token=None, payload=None, file_path=None, headers=None):
    started = time.perf_counter()
    status, response_headers, body = request(context["apiBase"], path, method, token, payload,
                                              file_path, headers, expected_status=None)
    ended = time.perf_counter()
    return {"status": status, "headers": response_headers, "body": body,
            "startedPerf": started, "endedPerf": ended, "elapsedMs": (ended - started) * 1000}


def http_duration(results):
    """整体窗口仅取首个HTTP开始到最后HTTP读完，不包含造文件或线程池初始化。"""
    return max(row["endedPerf"] for row in results) - min(row["startedPerf"] for row in results)


def parallel(count, operation, on_results=None):
    """同时起跑；只在内存保留响应，持久化结果必须由调用者聚合脱敏。"""
    barrier = threading.Barrier(count)

    def worker(index):
        try:
            barrier.wait(timeout=30)
            return operation(index)
        except Exception as error:
            return {"exception": type(error).__name__, "uncertain": True}

    with ThreadPoolExecutor(max_workers=count) as executor:
        results = list(executor.map(worker, range(count)))
    # 在不确定项断言之前交付脱敏计数，失败也保留真实HTTP/code分布而非只剩异常文本。
    if on_results:
        on_results(results)
    expect(not any(result.get("uncertain") for result in results),
           "并发请求存在结果不确定项，禁止自动重试或继续升压")
    return results, http_duration(results)


def successful(result):
    return result.get("status") == 200 and isinstance(result.get("body"), dict) and result["body"].get("code") == 0


def rejected(result, status, code):
    return result.get("status") == status and isinstance(result.get("body"), dict) and result["body"].get("code") == code


def batch_metrics(results, duration, sizes=None):
    """HTTP 短批统计；不将线程启动和结果汇总冒充五分钟稳态容量。"""
    delays = [result["elapsedMs"] for result in results]
    ok = sum(successful(result) for result in results)
    total_bytes = sum(sizes or [])
    return {"samples": len(results), "success": ok, "failures": len(results) - ok,
            "durationSeconds": round(duration, 4), "successfulRps": round(ok / max(duration, .001), 3),
            "p95Ms": round(percentile(delays, .95), 3), "p99Ms": round(percentile(delays, .99), 3),
            "lowSamplePercentiles": len(results) < 1000, "bytes": total_bytes,
            "MiBPerSecond": round(total_bytes / (1024 ** 2) / max(duration, .001), 3)}


def save_progress(evidence, mode, report):
    write_json(evidence / (mode + ".special.json"), report)
    print(json.dumps({"event": "SPECIAL_PROGRESS", "mode": mode,
                      "completedBatches": len(report.get("batches", []))}), flush=True)


def record_attempt(evidence, mode, report, operation, results, threads, repetition=None):
    """关键断言前只保存有限的状态/code/幂等标记分布，不持久化响应或票据。"""
    statuses, codes, ignored, rate_reasons = (collections.Counter() for _ in range(4))
    for row in results:
        statuses[str(row.get("status", "unknown"))] += 1
        body = row.get("body")
        codes[str(body.get("code", "missing")) if isinstance(body, dict) else "binary-or-unknown"] += 1
        data = body.get("data") if isinstance(body, dict) else None
        duplicate = data.get("duplicateIgnored") if isinstance(data, dict) else None
        ignored["true" if duplicate is True else "false" if duplicate is False else "missing"] += 1
        if isinstance(body, dict) and body.get("code") == 42901:
            reason = {"用户下载过于频繁": "user-threshold", "当前 IP 下载过于频繁": "ip-threshold",
                      "下载限流服务暂不可用，请稍后重试": "limiter-unavailable"}.get(body.get("message"), "other")
            rate_reasons[reason] += 1
    report["lastAttempt"] = {"operation": operation, "threads": threads, "repetition": repetition,
                             "httpStatusCounts": dict(statuses), "businessCodeCounts": dict(codes),
                             "duplicateIgnoredCounts": dict(ignored), "rateReasonCounts": dict(rate_reasons),
                             "uncertainResults": sum(bool(row.get("uncertain")) for row in results)}
    save_progress(evidence, mode, report)


def capture_failure_database_state(context, report):
    """并发写失败后独立查SQL状态，查询失败也不覆盖原始失败原因。"""
    target = report.get("favoriteTarget")
    if not target:
        return
    try:
        user_id, resource_id = int(target["userId"]), int(target["resourceId"])
        rows = json_rows(context, "SELECT JSON_OBJECT("
            f"'activeForUserResource',(SELECT COUNT(*) FROM favorite WHERE user_id={user_id} AND resource_id={resource_id} AND status=1),"
            f"'resourceFavoriteCount',(SELECT favorite_count FROM resource WHERE id={resource_id}),"
            f"'activeForResource',(SELECT COUNT(*) FROM favorite WHERE resource_id={resource_id} AND status=1));")
        report["failureDatabaseState"] = {**rows[0], "favoriteAggregateMismatches": favorite_discrepancies(context)}
    except Exception as error:
        report["failureDatabaseState"] = {"captureErrorType": type(error).__name__}


def pause(seconds, reason):
    """长窗口等待分段输出进度，便于主 Agent 持续汇报。"""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        remaining = deadline - time.monotonic()
        print(json.dumps({"event": "WAIT", "reason": reason, "remainingSeconds": math.ceil(remaining)}), flush=True)
        time.sleep(min(20, remaining))


def save_manifest(evidence, manifest):
    manifest.setdefault("baseResourceCount", len(manifest["resources"]))
    manifest.setdefault("baseStatusCounts", manifest.get("statusCounts", {}))
    manifest["statusCounts"] = {str(status): sum(row["status"] == status for row in manifest["resources"])
                                for status in range(4)}
    write_json(evidence / "manifest.json", manifest)


def source_file(evidence, mode, size):
    root = evidence / "special-files" / mode
    for path in (root, root.parent):
        if path.exists():
            metadata = path.lstat()
            expect(not path.is_symlink() and not getattr(metadata, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT,
                   "合成源目录包含链接/ReparsePoint")
    root.mkdir(parents=True, exist_ok=True)
    path = root / (uuid.uuid4().hex + ".txt")
    digest = create_text(path, mode + "-" + uuid.uuid4().hex, 0, size)
    return path, digest


def remember_upload(manifest, account, path, digest, size, result):
    expect(successful(result), "上传请求失败")
    data = result["body"]["data"]
    expect(data["fileMd5"] == digest and data["fileSize"] == size, "上传返回摘要/大小错误")
    item = {"fileId": int(data["fileId"]), "userId": int(account["userId"]),
            "md5": digest, "size": size, "path": str(path)}
    manifest.setdefault("uploadedFiles", []).append(item)
    return item


def favorite_discrepancies(context):
    return int(mysql(context, "SELECT COUNT(*) FROM resource r LEFT JOIN "
                     "(SELECT resource_id,COUNT(*) n FROM favorite WHERE status=1 GROUP BY resource_id) f "
                     "ON f.resource_id=r.id WHERE r.favorite_count<>COALESCE(f.n,0);"))


def correctness(context, manifest, evidence, report, case="all", start_threads=None):
    account = manifest["accounts"][0]
    admin = manifest["admin"]
    resource_id = int(manifest["approvedIds"][0])
    report["favoriteTarget"] = {"userId": int(account["userId"]), "resourceId": resource_id}
    output_mode = report["mode"]
    expect(favorite_discrepancies(context) == 0, "专项开始前收藏SQL聚合已不一致，禁止混入独立验证")
    manifest.setdefault("baseResourceCount", len(manifest["resources"]))
    concurrency_levels = (2, 5, 10, 20) if start_threads is None else (start_threads,)
    report["configuration"] = {"threads": list(concurrency_levels), "repetitions": 3}
    for concurrency in concurrency_levels:
        require_space(evidence, 200 * 1024)
        for repetition in range(3):
            def mark(operation, rows):
                record_attempt(evidence, output_mode, report, operation, rows, concurrency, repetition + 1)
            batch = {"threads": concurrency, "repetition": repetition + 1}
            if case in ("all", "favorite"):
                path = f"/resources/{resource_id}/favorites"
                active = int(mysql(context, f"SELECT COUNT(*) FROM favorite WHERE user_id={int(account['userId'])} "
                                   f"AND resource_id={resource_id} AND status=1;"))
                if active:
                    cleaned = timed_request(context, path, "DELETE", token=account["token"])
                    mark("favorite.prepare-remove", [cleaned])
                    expect(successful(cleaned), "并发收藏准备取消失败")
                results, _ = parallel(concurrency, lambda _: timed_request(context, path, "POST", token=account["token"]),
                                      lambda rows: mark("favorite.add", rows))
                expect(all(successful(row) for row in results), "并发收藏应全部幂等成功")
                expect(sum(not row["body"]["data"]["duplicateIgnored"] for row in results) == 1,
                       "并发收藏真正新增次数不为1")
                removed, _ = parallel(concurrency, lambda _: timed_request(context, path, "DELETE", token=account["token"]),
                                      lambda rows: mark("favorite.remove", rows))
                expect(sum(successful(row) for row in removed) == 1 and
                       sum(rejected(row, 404, 40401) for row in removed) == concurrency - 1,
                       "并发取消收藏结果不符合一次成功/其余404")
                expect(favorite_discrepancies(context) == 0, "收藏聚合计数不一致")
                batch.update(favoriteRealAdds=1, unfavoriteSuccess=1)

            if case == "favorite":
                report["batches"].append(batch)
                save_progress(evidence, output_mode, report)
                continue

            # 每批新文件与新资料，避免复用上一批的唯一约束结果。
            source, digest = source_file(evidence, "correctness", 2048)
            uploaded = timed_request(context, "/files", "POST", token=account["token"], file_path=source)
            mark("file.upload", [uploaded])
            file_item = remember_upload(manifest, account, source, digest, 2048, uploaded)
            title = "并发合成资料 " + uuid.uuid4().hex[:16]
            payload = {"fileId": file_item["fileId"], "title": title,
                       "categoryId": manifest["categories"][0]["categoryId"], "courseName": "合成并发课程",
                       "resourceType": 2, "tags": ["压测", "并发"]}
            created, _ = parallel(concurrency, lambda _: timed_request(context, "/resources", "POST",
                                                                      token=account["token"], payload=payload),
                                  lambda rows: mark("resource.create", rows))
            successes = [row for row in created if successful(row)]
            expect(len(successes) == 1 and sum(rejected(row, 400, 40002) for row in created) == concurrency - 1,
                   "有效资料并发防重不符合一次成功/其余40002")
            new_id = int(successes[0]["body"]["data"]["resourceId"])
            resource = {**file_item, "resourceId": new_id, "status": 0, "title": title}
            manifest["resources"].append(resource)
            save_manifest(evidence, manifest)
            approvals, _ = parallel(concurrency, lambda _: timed_request(
                context, f"/admin/resources/{new_id}/audit-approvals", "POST", token=admin["token"],
                payload={"auditReason": "本地并发专项"}), lambda rows: mark("audit.approve", rows))
            expect(sum(successful(row) for row in approvals) == 1 and
                   sum(rejected(row, 400, 40901) for row in approvals) == concurrency - 1,
                   "并发审核状态竞争结果不符合预期")
            audits = int(mysql(context, f"SELECT COUNT(*) FROM audit_record WHERE resource_id={new_id};"))
            report["lastAttempt"]["auditRows"] = audits
            save_progress(evidence, output_mode, report)
            expect(audits == 1, "并发审核产生多条流水")
            resource["status"] = 1
            manifest["approvedIds"].append(new_id)
            save_manifest(evidence, manifest)
            batch.update(createSuccess=1, auditSuccess=1, auditRows=audits, resourceId=new_id)
            expect(favorite_discrepancies(context) == 0, "资料独立专项后收藏SQL聚合不一致")
            report["batches"].append(batch)
            save_progress(evidence, output_mode, report)


def require_space(evidence, pending_bytes):
    expect(shutil.disk_usage(evidence).free >= 10 * 1024 ** 3 + pending_bytes,
           "磁盘不足10GiB安全余量加本批待新增字节")


def assert_file_check(result, hit, file_id=None):
    """未授权与不存在均不得泄露fileId，不能只校验secondUpload布尔值。"""
    expect(successful(result), "文件预检HTTP/业务码失败")
    data = result["body"]["data"]
    expect(data.get("secondUpload") is hit and data.get("fileId") == file_id, "文件预检秒传/授权字段不符")


def upload_prechecks(context, manifest, evidence, report):
    known = manifest["resources"][0]
    owner = next(row for row in manifest["accounts"] if row["userId"] == known["userId"])
    authorized = {int(row["userId"]) for row in json_rows(context,
                  f"SELECT JSON_OBJECT('userId',user_id) FROM user_file_authorization WHERE file_id={int(known['fileId'])};")}
    outsider = next((row for row in manifest["accounts"] if row["userId"] not in authorized), None)
    expect(outsider is not None, "没有可验证未授权预检的账号")
    path = f"/files/check?fileMd5={known['md5']}&fileSize={int(known['size'])}"
    for label, account, hit, file_id in (("file-check.owner", owner, True, known["fileId"]),
                                        ("file-check.unauthorized", outsider, False, None)):
        result = timed_request(context, path, token=account["token"])
        record_attempt(evidence, "upload", report, label, [result], 1)
        assert_file_check(result, hit, file_id)
    require_space(evidence, 4096)
    source, digest = source_file(evidence, "upload-precheck", 2048)
    expect(int(mysql(context, f"SELECT COUNT(*) FROM file_info WHERE file_md5='{digest}' AND file_size=2048;")) == 0,
           "预检miss样本在数据库已存在")
    cache_key = f"crp:cache:file:md5:{digest}:2048"
    reader = RedisReader(context)
    try:
        expect(reader.command("EXISTS", cache_key) == 0, "预检miss样本已有缓存")
        negative_ttl = None
        new_path = f"/files/check?fileMd5={digest}&fileSize=2048"
        for repetition in (1, 2):
            result = timed_request(context, new_path, token=owner["token"])
            record_attempt(evidence, "upload", report, "file-check.miss-then-negative", [result], 1, repetition)
            assert_file_check(result, False)
            expect(reader.command("GET", cache_key) == "NOT_FOUND", "不存在预检没有保留负缓存哨兵")
            negative_ttl = reader.command("TTL", cache_key)
            expect(0 < negative_ttl <= 300, "负缓存TTL不符合5分钟协议")
        uploaded = timed_request(context, "/files", "POST", token=owner["token"], file_path=source)
        record_attempt(evidence, "upload", report, "file-check.upload-after-negative", [uploaded], 1)
        item = remember_upload(manifest, owner, source, digest, 2048, uploaded)
        save_manifest(evidence, manifest)
        result = timed_request(context, new_path, token=owner["token"])
        record_attempt(evidence, "upload", report, "file-check.positive-after-upload", [result], 1)
        assert_file_check(result, True, item["fileId"])
        expect(reader.command("GET", cache_key) == str(item["fileId"]), "上传正缓存未覆盖负哨兵")
    finally:
        reader.close()
    report["batches"].append({"kind": "file-check", "ownerHit": True, "unauthorizedHidden": True,
                              "firstMiss": True, "negativeCacheVerified": True, "negativeTtlSeconds": negative_ttl,
                              "uploadOverwritesNegative": True})
    save_progress(evidence, "upload", report)


def upload_matrix(start_threads=None):
    """100起步保留大小维度；10MiB整包客户端按已知内存预算降至10线程。"""
    if start_threads is None:
        return [(size, concurrency, max(concurrency * 2, 20))
                for size in (100 * 1024, 1024 ** 2, 10 * 1024 ** 2) for concurrency in (1, 2, 5, 10)]
    return [(100 * 1024, start_threads, start_threads), (1024 ** 2, start_threads, start_threads),
            (10 * 1024 ** 2, min(start_threads, 10), 20)]


def upload(context, manifest, evidence, report, start_threads=None):
    report["transport"] = "Python urllib短批，无连接池/无keepalive；不是JMeter五分钟稳态容量"
    accounts = manifest["accounts"]
    required_accounts = 20 if start_threads is None else start_threads
    expect(len(accounts) >= required_accounts, "上传并发专项账号不足")
    upload_prechecks(context, manifest, evidence, report)
    # 80个各档大小文件的源副本+服务端副本总量约1.74GiB，额外去重批次只占少量空间。
    matrix = upload_matrix(start_threads)
    planned_bytes = sum(size * count * 2 for size, _, count in matrix) + 4 * 100 * 1024 * 2 + 4096
    expect(planned_bytes < 2 * 1024 ** 3, "上传源+服务端计划超过2GiB")
    report["configuration"] = {"requestedStartThreads": start_threads, "plannedSourceAndStoredBytes": planned_bytes,
        "uniqueContent": [{"size": size, "threads": threads, "requests": count} for size, threads, count in matrix],
        "adjustmentReason": "10MiB上传使用urllib整包缓冲，同机100并发会产生多份百MiB/ GiB级缓冲，降为10线程20请求"
                            if start_threads and start_threads > 10 else None}
    for concurrency in ((2, 5, 10, 20) if start_threads is None else (start_threads,)):
        require_space(evidence, 200 * 1024)
        source, digest = source_file(evidence, "upload-dedup", 100 * 1024)
        results, duration = parallel(concurrency, lambda index: timed_request(
            context, "/files", "POST", token=accounts[index]["token"], file_path=source),
            lambda rows: record_attempt(evidence, "upload", report, "upload.same-content", rows, concurrency))
        files = [remember_upload(manifest, accounts[index], source, digest, 100 * 1024, row)
                 for index, row in enumerate(results)]
        expect(len({row["fileId"] for row in files}) == 1, "同内容并发上传返回不同fileId")
        stored = json_rows(context, "SELECT JSON_OBJECT('id',id,'path',storage_path,'size',file_size) "
                           f"FROM file_info WHERE file_md5='{digest}' AND file_size={100 * 1024};")
        expect(len(stored) == 1 and digest_file(stored[0]["path"]) == digest and
               Path(stored[0]["path"]).stat().st_size == 100 * 1024, "重复上传物理文件不唯一或内容错误")
        for item in files:
            expect(int(mysql(context, f"SELECT COUNT(*) FROM user_file_authorization WHERE user_id={item['userId']} "
                                    f"AND file_id={item['fileId']};")) == 1, "重复上传账号缺少授权")
        save_manifest(evidence, manifest)
        report["batches"].append({"kind": "same-content", "threads": concurrency,
                                  **batch_metrics(results, duration, [100 * 1024] * concurrency),
                                  "uniqueFileRows": 1, "authorizedUsers": concurrency})
        save_progress(evidence, "upload", report)
    for size, concurrency, count in matrix:
            require_space(evidence, count * size * 2)
            sources = [source_file(evidence, "upload-new", size) for _ in range(count)]
            # 固定worker数；文件在计时前生成，HTTP每次内容唯一，禁止生成成本混入上传耗时。
            barrier = threading.Barrier(concurrency)
            def one(index):
                if index < concurrency:
                    barrier.wait(timeout=30)
                return timed_request(context, "/files", "POST", token=accounts[index % len(accounts)]["token"],
                                     file_path=sources[index][0])
            with ThreadPoolExecutor(max_workers=concurrency) as executor:
                results = list(executor.map(one, range(count)))
            duration = http_duration(results)
            record_attempt(evidence, "upload", report, "upload.unique-content", results, concurrency)
            for index, row in enumerate(results):
                remember_upload(manifest, accounts[index % len(accounts)], sources[index][0], sources[index][1], size, row)
            save_manifest(evidence, manifest)
            report["batches"].append({"kind": "unique-content", "threads": concurrency, "fileSize": size,
                                      **batch_metrics(results, duration, [size] * count)})
            save_progress(evidence, "upload", report)


class RedisReader:
    """专用本机RESP客户端；对账只读，冷缓存实验仅删除一个明确的详情Key。"""
    def __init__(self, context):
        host = context.get("redisHost", "127.0.0.1")
        expect(host in ("127.0.0.1", "localhost", "::1"), "Redis对账只允许本机实例")
        self.socket = socket.create_connection((host, int(context["redisPort"])), timeout=5)
        self.stream = self.socket.makefile("rb")
        if context.get("redisPassword"):
            self.command("AUTH", context["redisPassword"])
        self.command("SELECT", context.get("redisDatabase", 0))

    def close(self):
        self.stream.close()
        self.socket.close()

    def read(self):
        prefix = self.stream.read(1)
        expect(bool(prefix), "Redis连接意外结束")
        line = self.stream.readline().rstrip(b"\r\n")
        if prefix == b"-":
            raise RuntimeError("Redis对账命令失败")
        if prefix == b"+":
            return line.decode()
        if prefix == b":":
            return int(line)
        if prefix == b"$":
            size = int(line)
            if size == -1:
                return None
            content = self.stream.read(size)
            expect(len(content) == size and self.stream.read(2) == b"\r\n", "Redis响应不完整")
            return content.decode()
        if prefix == b"*":
            return [self.read() for _ in range(int(line))]
        raise RuntimeError("不支持的Redis响应")

    def command(self, *arguments):
        parts = [str(argument).encode() for argument in arguments]
        packet = b"*" + str(len(parts)).encode() + b"\r\n" + b"".join(
            b"$" + str(len(part)).encode() + b"\r\n" + part + b"\r\n" for part in parts)
        self.socket.sendall(packet)
        return self.read()

    def snapshot(self):
        prefix = "crp:stats:resource:download:syncing:"
        pointer = self.command("GET", prefix + "current")
        cursor, batches = "0", {}
        while True:
            cursor, keys = self.command("SCAN", cursor, "MATCH", prefix + "*", "COUNT", 100)
            for key in keys:
                if key != prefix + "current":
                    # 后台任务可在SCAN与TYPE之间正常删除已完成批次，不能把这种读竞态当故障。
                    kind = self.command("TYPE", key)
                    if kind == "none":
                        continue
                    expect(kind == "hash", "同步批次出现非Hash键")
                    batches[key] = self.command("HLEN", key)
            if cursor == "0":
                break
        delta = self.command("HLEN", "crp:stats:resource:download:delta")
        return {"deltaFields": delta, "hasCurrent": pointer is not None, "batchFields": batches,
                "empty": delta == 0 and pointer is None and not any(batches.values())}


def wait_drained(context, seconds=300):
    """两次空状态至少相隔60秒，不把delta刚RENAME后的空窗口当作同步完成。"""
    reader = RedisReader(context)
    first_empty = None
    started = time.monotonic()
    snapshots = []
    try:
        while time.monotonic() - started < seconds:
            snapshot = reader.snapshot()
            snapshot["elapsedSeconds"] = round(time.monotonic() - started, 3)
            snapshots.append(snapshot)
            if snapshot["empty"]:
                first_empty = first_empty or time.monotonic()
                if time.monotonic() - first_empty >= 60:
                    return snapshots
            else:
                first_empty = None
            print(json.dumps({"event": "DRAIN", **snapshot}), flush=True)
            time.sleep(10)
    finally:
        reader.close()
    raise RuntimeError("下载增量未在观察预算内连续跨周期排空")


class Downloads:
    def __init__(self, context, on_sign=None):
        self.context = context
        self.on_sign = on_sign
        self.counted = collections.Counter()
        self.post_success = 0
        self.get_complete = 0

    def sign(self, account, resource_id):
        result = timed_request(self.context, f"/resources/{resource_id}/download-records", "POST", token=account["token"])
        if self.on_sign:
            self.on_sign(result)
        return self.register_signature(result, resource_id), result

    def register_signature(self, result, resource_id):
        """并发HTTP结束后由主线程统一登记，禁止共享tracker在线程内递增。"""
        if successful(result):
            ticket = result["body"]["data"]
            expect(ticket["resourceId"] == resource_id and ticket["downloadTicket"], "下载票据字段不完整")
            expect(ticket["downloadUrl"] == f"/api/v1/download-records/{ticket['downloadRecordId']}/file", "下载URL不符")
            self.post_success += 1
            self.counted[resource_id] += bool(ticket["counted"])
            return ticket
        return None

    def get(self, account, ticket):
        return timed_request(self.context, f"/download-records/{int(ticket['downloadRecordId'])}/file",
                             token=account["token"], headers={"X-Download-Ticket": ticket["downloadTicket"]})

    def validate_file(self, result, resource):
        headers = {key.lower(): value for key, value in result["headers"].items()}
        content = result["body"]
        expect(result["status"] == 200 and isinstance(content, bytes), "文件GET未返回成功二进制")
        expect(headers.get("content-type", "").startswith("application/octet-stream") and
               "attachment" in headers.get("content-disposition", ""), "下载响应类型/附件头不符")
        expect(len(content) == resource["size"] and hashlib.md5(content).hexdigest() == resource["md5"],
               "下载字节数或摘要不符")
        expect(int(headers.get("content-length", -1)) == resource["size"], "下载Content-Length不符")
        self.get_complete += 1


def collect_signature_batch(tracker, accounts, rows, resource_id):
    """HTTP并发结束后调用；所有可确认成功都由当前主线程计入账本。"""
    tickets, owners = [], []
    for index, row in enumerate(rows):
        ticket = tracker.register_signature(row, resource_id)
        if ticket is not None:
            tickets.append(ticket)
            owners.append(accounts[index])
    return tickets, owners


def assert_ip_burst(rows, tickets, requested):
    allowed = min(requested, 30)
    denied = [row for row in rows if not successful(row)]
    expect(len(rows) == requested and len(tickets) == allowed and len(denied) == requested - allowed and
           all(rejected(row, 429, 42901) and row["body"].get("message") == "当前 IP 下载过于频繁" for row in denied),
           "并发签票未严格符合IP30阈值；不能把Redis故障或用户限流当正常拒绝")
    return allowed, len(denied)


def download(context, manifest, evidence, report, start_threads=None):
    accounts = manifest["accounts"]
    expect(len(accounts) >= 60, "下载规则/突发专项至少需要60账号")
    expect(start_threads is None or len(accounts) >= start_threads, "签票突发账号不足")
    resources = [row for row in manifest["resources"] if row["status"] == 1][:3]
    expect(len(resources) == 3, "下载至少需要三个已通过资料")
    ids = ",".join(str(int(row["resourceId"])) for row in resources)
    # 初始delta可能已被RENAME隔离，单次跨命令读到空不能证明无历史批次。
    # 和最终对账使用同一跨周期稳定空门禁，然后再记录MySQL before，避免混入历史增量。
    report["initialDrainSnapshots"] = wait_drained(context)
    before = {int(row["id"]): int(row["count"]) for row in json_rows(
        context, f"SELECT JSON_OBJECT('id',id,'count',download_count) FROM resource WHERE id IN ({ids});")}
    tracker = Downloads(context, lambda row: record_attempt(evidence, "download", report, "download.sign", [row], 1))
    pause(62, "下载专项开始前恢复限流窗口")
    low_counted, low_samples = [], []
    for index in range(12):
        started = time.monotonic()
        owner = next(account for account in accounts if account["userId"] == resources[index % 3]["userId"])
        resource = resources[index % 3]
        ticket, post = tracker.sign(owner, resource["resourceId"])
        record_attempt(evidence, "download", report, "low-rate.sign", [post], 1, index + 1)
        expect(ticket is not None, "低速签票失败")
        result = tracker.get(owner, ticket)
        record_attempt(evidence, "download", report, "low-rate.file", [result], 1, index + 1)
        tracker.validate_file(result, resource)
        low_counted.append(bool(ticket["counted"]))
        low_samples.append({"resourceId": resource["resourceId"], "bytes": resource["size"],
                            "postMs": round(post["elapsedMs"], 3), "getCompleteMs": round(result["elapsedMs"], 3),
                            "counted": bool(ticket["counted"])})
        if index < 11:
            time.sleep(max(0, 5 - (time.monotonic() - started)))
    expect(low_counted == [True] * 3 + [False] * 9, "首次/十分钟去重计数与预期不符")
    report["batches"].append({"kind": "low-rate-complete", "posts": 12, "completeGets": 12,
                              "countedTrue": sum(low_counted), "intervalSeconds": 5, "samples": low_samples,
                              "latencyNote": "GET耗时为读取完整内容，未单独测量首字节"})
    save_progress(evidence, "download", report)
    small = resources[0]

    ticket, _ = tracker.sign(accounts[5], small["resourceId"])
    expect(ticket is not None, "重放专项签票失败")
    replay_threads = start_threads or 5
    results, _ = parallel(replay_threads, lambda _: tracker.get(accounts[5], ticket),
                          lambda rows: record_attempt(evidence, "download", report, "ticket.replay", rows, replay_threads))
    expect(sum(row["status"] == 200 for row in results) == 1 and
           sum(rejected(row, 400, 40901) for row in results) == replay_threads - 1, "一次票据并发重放结果错误")
    tracker.validate_file(next(row for row in results if row["status"] == 200), small)
    report["batches"].append({"kind": "ticket-replay", "requests": replay_threads, "success": 1, "rejected": replay_threads - 1})
    expired, _ = tracker.sign(accounts[6], small["resourceId"])
    expect(expired is not None, "过期专项签票失败")
    pause(62, "等待一次性票据过期")
    expired_result = tracker.get(accounts[6], expired)
    record_attempt(evidence, "download", report, "ticket.expired", [expired_result], 1)
    expect(rejected(expired_result, 400, 40901), "过期票据未正确拒绝")
    wrong, _ = tracker.sign(accounts[7], small["resourceId"])
    expect(wrong is not None, "跨账号专项签票失败")
    wrong_result = tracker.get(accounts[8], wrong)
    record_attempt(evidence, "download", report, "ticket.wrong-account", [wrong_result], 1)
    expect(rejected(wrong_result, 400, 40901), "换账号读取票据未拒绝")
    tracker.validate_file(tracker.get(accounts[7], wrong), small)
    report["batches"].append({"kind": "ticket-expiry-and-owner", "expiredRejected": True,
                              "wrongAccountRejected": True, "ownerStillSucceeded": True})
    save_progress(evidence, "download", report)

    for kind, total, users, message in (("user-limit", 11, [accounts[10]], "用户下载过于频繁"),
                                       ("ip-limit", 31, accounts[20:24], "当前 IP 下载过于频繁")):
        pause(62, "恢复窗口后验证" + kind)
        allowed = 0
        rate_results = []
        for index in range(total):
            ticket, result = tracker.sign(users[index % len(users)], small["resourceId"])
            rate_results.append(result)
            record_attempt(evidence, "download", report, kind, rate_results, 1)
            if index < total - 1:
                expect(ticket is not None, "达到预期阈值前出现拒绝")
                allowed += 1
            else:
                expect(ticket is None and rejected(result, 429, 42901) and result["body"].get("message") == message,
                       "限流拒绝码/原因不符，不能把Redis故障算限流成功")
        report["batches"].append({"kind": kind, "allowed": allowed, "thresholdRejected": 1})
        save_progress(evidence, "download", report)

    for concurrency in ((1, 5, 10, 20) if start_threads is None else (start_threads,)):
        pause(62, "文件突发签票前恢复窗口")
        tickets = []
        first_issued = time.monotonic()
        ticket_accounts = []
        if start_threads is None:
            for index in range(concurrency):
                ticket, _ = tracker.sign(accounts[40 + index], small["resourceId"])
                expect(ticket is not None, "突发文件预签失败")
                tickets.append(ticket)
                ticket_accounts.append(accounts[40 + index])
            burst_result = {}
        else:
            def collect_signatures(rows):
                record_attempt(evidence, "download", report, "signature.concurrent-burst", rows, concurrency)
                collected, owners = collect_signature_batch(tracker, accounts, rows, small["resourceId"])
                tickets.extend(collected)
                ticket_accounts.extend(owners)
                report["knownPostSuccess"] = tracker.post_success
                report["knownCountedTrue"] = sum(tracker.counted.values())
                save_progress(evidence, "download", report)
            signed, _ = parallel(concurrency, lambda index: timed_request(context,
                f"/resources/{small['resourceId']}/download-records", "POST", token=accounts[index]["token"]), collect_signatures)
            allowed, denied = assert_ip_burst(signed, tickets, concurrency)
            burst_result = {"requestedThreads": concurrency, "allowed": allowed, "rejected": denied,
                            "effectiveGetThreads": allowed, "adjustmentReason": "单IP每60秒30次签票，100线程不能取得100张有效票据"}
        expect(time.monotonic() - first_issued < 55, "预签耗时过长，不能在票据期内完成公平突发")
        effective_threads = len(tickets)
        results, duration = parallel(effective_threads, lambda index: tracker.get(ticket_accounts[index], tickets[index]),
                                     lambda rows: record_attempt(evidence, "download", report, "presigned-file-burst", rows, effective_threads))
        for row in results:
            tracker.validate_file(row, small)
        delays = [row["elapsedMs"] for row in results]
        report["batches"].append({"kind": "presigned-file-burst", "threads": concurrency,
                                  **burst_result, "completeGets": effective_threads, "durationSeconds": round(duration, 4),
                                  "bytes": effective_threads * small["size"],
                                  "MiBPerSecond": round(effective_threads * small["size"] / 1024 ** 2 / max(duration, .001), 3),
                                  "p95Ms": round(percentile(delays, .95), 3), "lowSamplePercentiles": True,
                                  "p99Ms": round(percentile(delays, .99), 3),
                                  "note": "短时预签票GET，不代表持续完整下载业务容量"})
        save_progress(evidence, "download", report)
    report["drainSnapshots"] = wait_drained(context)
    after = {int(row["id"]): int(row["count"]) for row in json_rows(
        context, f"SELECT JSON_OBJECT('id',id,'count',download_count) FROM resource WHERE id IN ({ids});")}
    reconciliation = []
    for resource_id in before:
        delta = after[resource_id] - before[resource_id]
        expected = tracker.counted[resource_id]
        expect(delta == expected, "下载增量与所有专项签票counted=true汇总不一致")
        reconciliation.append({"resourceId": resource_id, "before": before[resource_id], "after": after[resource_id],
                               "countedTrue": expected, "delta": delta})
    report["downloadReconciliation"] = reconciliation
    report["postSuccess"] = tracker.post_success
    report["completeGets"] = tracker.get_complete
    report["countedTrue"] = sum(tracker.counted.values())
    report["uncertainWrites"] = 0


def verify_manifest_links(manifest, files, resources, relations):
    """同时校验文件内容身份、上传授权与资料归属，避免只核授权而漏掉fileId错绑。"""
    files_by_id = {int(row["fileId"]): row for row in files}
    relation_set = {(int(row["userId"]), int(row["fileId"])) for row in relations}
    # resources和独立上传都带fileId/md5/size/userId；source path与服务端path本就不同，不作路径等值比较。
    for uploaded in manifest["resources"] + manifest.get("uploadedFiles", []):
        file_id = int(uploaded["fileId"])
        expect(file_id in files_by_id, "清单文件不存在或不处于正常状态")
        actual_file = files_by_id[file_id]
        expect(actual_file["md5"] == uploaded["md5"] and int(actual_file["size"]) == int(uploaded["size"]),
               "清单与file_info的摘要/大小不一致")
        expect((int(uploaded["userId"]), file_id) in relation_set, "清单上传用户缺授权")
    actual_resources = {int(row["id"]): (int(row["status"]), int(row["fileId"]), int(row["userId"]))
                        for row in resources}
    expected_resources = {int(row["resourceId"]): (int(row["status"]), int(row["fileId"]), int(row["userId"]))
                          for row in manifest["resources"]}
    expect(len(expected_resources) == len(manifest["resources"]), "清单包含重复资料ID")
    expect(actual_resources == expected_resources, "数据库资料状态/数量/file_id/uploader_id与清单不符")
    return {resource_id: values[0] for resource_id, values in actual_resources.items()}


def verify(context, manifest, evidence, report, start_threads=None):
    checks = {}
    checks["favoriteCountMismatches"] = favorite_discrepancies(context)
    checks["favoriteDuplicateRelations"] = int(mysql(context, "SELECT COUNT(*) FROM "
        "(SELECT user_id,resource_id FROM favorite GROUP BY user_id,resource_id HAVING COUNT(*)>1) x;"))
    checks["activeResourceDuplicates"] = int(mysql(context, "SELECT COUNT(*) FROM "
        "(SELECT uploader_id,file_id FROM resource WHERE status IN(0,1) GROUP BY uploader_id,file_id HAVING COUNT(*)>1) x;"))
    checks["fileDigestSizeDuplicates"] = int(mysql(context, "SELECT COUNT(*) FROM "
        "(SELECT file_md5,file_size FROM file_info GROUP BY file_md5,file_size HAVING COUNT(*)>1) x;"))
    expect(not any(checks.values()), "数据库唯一关系或收藏计数对账失败")
    files = json_rows(context, "SELECT JSON_OBJECT('fileId',id,'path',storage_path,'md5',file_md5,'size',file_size) "
                      "FROM file_info WHERE status=1;")
    upload_root = Path(context["uploadDir"]).resolve()
    expected_paths = set()
    for item in files:
        path = Path(item["path"]).resolve()
        expect(path.is_relative_to(upload_root), "数据库物理路径不属于专用上传目录")
        expect(path.is_file() and path.stat().st_size == item["size"] and digest_file(path) == item["md5"],
               "物理文件缺失、大小或摘要不匹配")
        expected_paths.add(path)
    actual_paths = set()
    for path in upload_root.rglob("*"):
        # readiness探针会瞬时创建/删除；在lstat之前排除，避免正常探针消失造成假失败。
        if path.name.startswith(".readiness-") and path.suffix == ".tmp":
            continue
        metadata = path.lstat()
        expect(not path.is_symlink() and not getattr(metadata, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT,
               "上传目录存在符号链接/ReparsePoint")
        if path.is_file():
            expect(path.suffix != ".part", "停压后仍存在.part文件")
            actual_paths.add(path.resolve())
    expect(actual_paths == expected_paths, "上传目录有多余或缺失物理文件")
    relations = json_rows(context, "SELECT JSON_OBJECT('userId',user_id,'fileId',file_id) FROM user_file_authorization;")
    resources = json_rows(context, "SELECT JSON_OBJECT('id',id,'status',status,'fileId',file_id,'userId',uploader_id) FROM resource;")
    resource_map = verify_manifest_links(manifest, files, resources, relations)
    audits = json_rows(context, "SELECT JSON_OBJECT('resourceId',resource_id,'before',before_status,'after',after_status) "
                       "FROM audit_record ORDER BY id;")
    states = {resource_id: 0 for resource_id in resource_map}
    for row in audits:
        resource_id = int(row["resourceId"])
        expect(resource_id in states and states[resource_id] == int(row["before"]), "审核流水前状态链不一致")
        states[resource_id] = int(row["after"])
    expect(states == resource_map, "审核流水终态与资料状态不一致")
    report["checks"] = {**checks, "normalFiles": len(files), "physicalFiles": len(actual_paths),
                        "authorizationRows": len(relations), "resourceRows": len(resources),
                        "auditRows": len(audits), "partFiles": 0, "orphanPhysicalFiles": 0,
                        "manifestAuthorizationsVerified": True, "manifestFileIdentityVerified": True,
                        "resourceFileAndUploaderVerified": True, "auditStateChainsVerified": True}
    public_visibility_and_cache(context, manifest, evidence, report, start_threads)


def public_visibility_and_cache(context, manifest, evidence, report, start_threads=None):
    """停压后验证公开边界，再只删除单个详情缓存制造20并发冷回填。"""
    approved = next(row for row in manifest["resources"] if row["status"] == 1)
    result = timed_request(context, f"/resources/{int(approved['resourceId'])}")
    record_attempt(evidence, "verify", report, "visibility.approved", [result], 1)
    expect(successful(result) and result["body"]["data"]["status"] == 1 and
           result["body"]["data"]["resourceId"] == approved["resourceId"], "已通过资料公开详情不符")
    for status in (0, 2, 3):
        hidden = next(row for row in manifest["resources"] if row["status"] == status)
        result = timed_request(context, f"/resources/{int(hidden['resourceId'])}")
        record_attempt(evidence, "verify", report, "visibility.non-public", [result], 1, status)
        expect(rejected(result, 400, 40901), "非公开资料未正确拒绝公开详情")
    missing_id = int(mysql(context, "SELECT COALESCE(MAX(id),0)+10000 FROM resource;"))
    result = timed_request(context, f"/resources/{missing_id}")
    record_attempt(evidence, "verify", report, "visibility.missing", [result], 1)
    expect(rejected(result, 404, 40401), "不存在资料详情未正确返回404")
    result = timed_request(context, "/users/me")
    record_attempt(evidence, "verify", report, "visibility.no-token", [result], 1)
    expect(rejected(result, 401, 40101), "无Token个人接口未拒绝")
    key = f"crp:cache:resource:detail:{int(approved['resourceId'])}"
    reader = RedisReader(context)
    try:
        # 仅此一个已确认压测资料的详情Key，不删除锁、黑名单、票据或统计数据。
        deleted = reader.command("DEL", key)
        expect(reader.command("EXISTS", key) == 0, "指定详情缓存未进入冷状态")
        cold_threads = start_threads or 20
        rows, duration = parallel(cold_threads, lambda _: timed_request(context, f"/resources/{int(approved['resourceId'])}"),
            lambda results: record_attempt(evidence, "verify", report, "cache.cold-detail", results, cold_threads))
        expect(all(successful(row) and row["body"]["data"]["resourceId"] == approved["resourceId"]
                   and row["body"]["data"]["status"] == 1 for row in rows), "冷缓存并发详情不正确")
        ttl = reader.command("TTL", key)
        expect(0 < ttl <= 2100, "冷回填详情TTL不符合30～35分钟协议")
    finally:
        reader.close()
    report["visibility"] = {"approvedVisible": True, "pendingRejected": True, "rejectedHidden": True,
                            "offlineHidden": True, "missing404": True, "noToken401": True}
    report["coldCache"] = {"threads": cold_threads, "success": len(rows), "deletedTargetKeys": deleted, "ttlSeconds": ttl,
                           **batch_metrics(rows, duration), "sqlQueriesMeasured": False,
                           "note": "只验证返回与回填TTL，不声称SQL只执行一次"}


def report_mode(mode, case="all"):
    """独立用例有独立证据名；已确认的收藏失败不会阻塞资料/审核独立验证。"""
    expect(case in ("all", "favorite", "resource"), "不支持的correctness用例")
    expect(mode == "correctness" or case == "all", "--case 仅适用于correctness")
    return f"correctness-{case}" if mode == "correctness" and case != "all" else mode


def execute(context_path, mode, case="all", start_threads=None):
    expect(start_threads is None or 1 <= start_threads <= 100, "start-threads需为1～100")
    context = load_context(context_path)
    evidence = Path(context["evidenceDir"])
    manifest = read_json(evidence / "manifest.json")
    expect(manifest.get("status") == "COMPLETE" and manifest.get("apiBase") == context["apiBase"],
           "专项清单未完成或API地址不符")
    # 兼容首轮旧清单没有 mysqlTarget；用实时SQL/API分类映射绑定，禁止对目标作写入试探。
    verify_category_binding(context, manifest["categories"])
    output_mode = report_mode(mode, case)
    output = evidence / (output_mode + ".special.json")
    expect(not output.exists(), "专项已有结果，拒绝自动重跑或覆盖")
    report = {"mode": output_mode, "case": case, "requestedStartThreads": start_threads,
              "status": "RUNNING", "started": time.time(), "batches": []}
    save_progress(evidence, output_mode, report)
    try:
        if mode == "correctness":
            correctness(context, manifest, evidence, report, case, start_threads)
        else:
            {"upload": upload, "download": download, "verify": verify}[mode](context, manifest, evidence, report, start_threads)
        report.update(status="PASSED", ended=time.time())
    except Exception as error:
        capture_failure_database_state(context, report)
        report.update(status="FAILED", ended=time.time(), errorType=type(error).__name__,
                      failureReason=str(error), uncertainWrites="HTTP超时/连接错误时需人工核对；不自动重试")
        save_progress(evidence, output_mode, report)
        raise
    save_progress(evidence, output_mode, report)
    print(json.dumps({"event": "SPECIAL_FINISHED", "mode": output_mode, "status": report["status"]}), flush=True)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--mode", required=True, choices=("correctness", "upload", "download", "verify"))
    parser.add_argument("--case", default="all", choices=("all", "favorite", "resource"))
    parser.add_argument("--start-threads", type=int, default=None)
    arguments = parser.parse_args(argv)
    if arguments.mode != "correctness" and arguments.case != "all":
        parser.error("--case 仅适用于 --mode correctness")
    return execute(arguments.context, arguments.mode, arguments.case, arguments.start_threads)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"专项停止：{type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
