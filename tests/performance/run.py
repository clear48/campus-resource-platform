"""运行本地 JMeter 并生成无凭据的分场景统计；0退出码不能代替业务断言。"""

import argparse
import collections
import csv
import ctypes
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

from common import load_context, read_json, write_json
from generate_plan import generate
from seed import mysql_target, verify_category_binding


def percentile(values, fraction):
    """使用 nearest-rank；低样本 P99 需由报告明确标为参考值。"""
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)] if ordered else None


class SampleStats:
    """只保留延迟序列和计数，不把大规模 JTL 全部字典加载进内存。"""
    def __init__(self):
        self.delays = []
        self.successes = 0
        self.start = None
        self.end = None
        self.response_codes = collections.Counter()

    def add(self, timestamp, elapsed, success, code):
        self.delays.append(elapsed)
        self.successes += success
        self.start = timestamp if self.start is None else min(self.start, timestamp)
        self.end = timestamp + elapsed if self.end is None else max(self.end, timestamp + elapsed)
        self.response_codes[code] += 1

    def result(self, common_window=None):
        if not self.delays:
            return {"samples": 0, "valid": False}
        # 混合场景各label使用共同观测窗口，不能用稀疏label自身的短活跃跨度夸大RPS。
        window_start, window_end = common_window or (self.start, self.end)
        seconds = max((window_end - window_start) / 1000, 0.001)
        samples = len(self.delays)
        failures = samples - self.successes
        return {"samples": samples, "valid": True, "success": self.successes,
                "failures": failures, "failureRate": failures / samples,
                "windowSeconds": round(seconds, 3), "successfulRps": round(self.successes / seconds, 3),
                "attemptedRps": round(samples / seconds, 3), "p50Ms": percentile(self.delays, .50),
                "p95Ms": percentile(self.delays, .95), "p99Ms": percentile(self.delays, .99),
                "maxMs": max(self.delays), "meanMs": round(sum(self.delays) / samples, 3),
                "responseCodes": dict(self.response_codes),
                "startEpoch": window_start / 1000, "endEpoch": window_end / 1000,
                "activeStartEpoch": self.start / 1000, "activeEndEpoch": self.end / 1000,
                "activeWindowSeconds": round(max((self.end - self.start) / 1000, .001), 3)}


def summarize(path, exclude=0, target_threads=None, duration=None, origin=None):
    """JTL按完成顺序写入；两遍流式读取以真实最早start排除预热，不缓存CSV全表。"""
    overall = SampleStats()
    if origin is not None and (duration is None or duration<=exclude):
        raise ValueError("显式origin必须配正数固定稳态预算")
    labels = collections.defaultdict(SampleStats)
    first, last_end = None, None
    with Path(path).open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            timestamp = int(row["timeStamp"])
            first = timestamp if first is None else min(first, timestamp)
            end = timestamp + int(row["elapsed"])
            last_end = end if last_end is None else max(last_end, end)
    peak_group = peak_all = full_samples = kept = correctness_assertions_failed = 0
    # 原生线程组调度起点优先；旧调用不传origin时保留历史HTTP-min口径供旧结果检索。
    origin_ms = round(origin*1000) if origin is not None else first
    steady_start_ms = origin_ms+exclude*1000 if origin_ms is not None else None
    steady_end_ms = origin_ms+duration*1000 if origin is not None else None
    # 桶以样本开始时间归属，绝不把长尾请求的elapsed填充为持续并发；空桶与混入低线程的桶都失败。
    budget = max(0, duration-exclude) if duration is not None else None
    expected_buckets = math.ceil(budget) if budget is not None else 0
    bucket_flags = {}
    fields_available = True
    error_kinds = collections.Counter()
    with Path(path).open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            timestamp = int(row["timeStamp"])
            if timestamp < steady_start_ms or (steady_end_ms is not None and timestamp>=steady_end_ms):
                continue
            kept += 1
            correctness_assertions_failed += row["responseCode"]=="200" and row["success"]!="true"
            fields_available = fields_available and bool(row.get("grpThreads")) and bool(row.get("allThreads"))
            group, all_threads = int(row.get("grpThreads") or 0), int(row.get("allThreads") or 0)
            peak_group, peak_all = max(peak_group, group), max(peak_all, all_threads)
            full_samples += bool(target_threads and group >= target_threads and all_threads >= target_threads)
            offset = (timestamp-steady_start_ms)/1000
            if budget is not None and 0 <= offset < budget:
                bucket = int(offset)
                flag = 1 if target_threads and group >= target_threads and all_threads >= target_threads else 2
                bucket_flags[bucket] = bucket_flags.get(bucket,0) | flag
            for kind in classify_tool_error(row.get("responseCode", "") + " " + row.get("failureMessage", "")):
                error_kinds[kind] += 1
            values = (timestamp, int(row["elapsed"]), row["success"] == "true", row["responseCode"])
            overall.add(*values)
            labels[row["label"]].add(*values)
    # 固定窗口内启动的请求即使在窗口后返回，仍保留其最终延迟/错误；RPS分母固定预算，不被慢尾撑大。
    window = (steady_start_ms,steady_end_ms) if origin is not None else ((overall.start, overall.end) if overall.start is not None else None)
    full_seconds = sum(min(1,budget-index) for index,flag in bucket_flags.items() if flag==1) if budget else 0
    return {"overall": overall.result(window), "labels": {name: stats.result(window) for name, stats in labels.items()},
            "threadCoverage": {"fieldsAvailable": fields_available and kept > 0, "peakGrpThreads": peak_group,
                "peakAllThreads": peak_all, "targetThreads": target_threads, "fullTargetSamples": full_samples,
                "fullTargetSampleFraction": full_samples / kept if kept else 0,
                "steadyStartEpoch":steady_start_ms/1000 if steady_start_ms is not None else None,
                "steadyEndEpoch":steady_end_ms/1000 if steady_end_ms is not None else None,
                "timeOrigin":"native_thread_group" if origin is not None else "legacy_http_min",
                "expectedSteadyBuckets":expected_buckets, "expectedSteadySeconds":budget,
                "fullTargetBuckets":sum(flag==1 for flag in bucket_flags.values()),
                "fullTargetSeconds":full_seconds, "fullTargetTimeFraction":full_seconds/budget if budget else 0,
                "effectiveSteadySeconds": max(0, (last_end-first)/1000-exclude) if first is not None else 0},
            "correctnessAssertionsFailed":correctness_assertions_failed, "toolErrors": dict(error_kinds)}


def classify_tool_error(text):
    """只保存错误分类，原始日志可能含本地路径，不复制进公开评价。"""
    kinds = []
    for kind, markers in {"BIND": ("BindException", "Address already in use", "Cannot assign requested address"),
                          "OOM": ("OutOfMemoryError", "unable to create native thread", "unable to create new native thread", "pthread_create failed"),
                          "SCRIPT": ("ScriptException", "Problem in JSR223", "Error in JSR223")}.items():
        if any(marker.lower() in text.lower() for marker in markers):
            kinds.append(kind)
    return kinds


def scan_tool_logs(*paths):
    errors = collections.Counter()
    for path in paths:
        if Path(path).exists():
            with Path(path).open(encoding="utf-8", errors="replace") as stream:
                for line in stream:
                    errors.update(classify_tool_error(line))
    return dict(errors)


def native_group_start(path):
    """单线程组计划的原生调度时间；缺失/多组时不猜测HTTP首样本作为调度起点。"""
    origins=[]
    if Path(path).exists():
        with Path(path).open(encoding="utf-8",errors="replace") as stream:
            for line in stream:
                match=re.search(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d{3}).*?ThreadGroup:\s+Starting thread group\.\.\.",line)
                if match:
                    origins.append(datetime.strptime(match[1],"%Y-%m-%d %H:%M:%S,%f").replace(tzinfo=timezone(timedelta(hours=8))).timestamp())
    return origins[0] if len(origins)==1 else None


def thread_activity(path, steady_start, budget, target):
    """以独立INFO生命周期积分实际活跃线程；请求稀疏不等于没有并发等待。"""
    events = []
    zone = timezone(timedelta(hours=8))
    if Path(path).exists():
        with Path(path).open(encoding="utf-8", errors="replace") as stream:
            for line in stream:
                match = re.search(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,\d{3}).*?Thread (started|finished): (.+?)\s*$", line)
                if match:
                    stamp = datetime.strptime(match[1],"%Y-%m-%d %H:%M:%S,%f").replace(tzinfo=zone).timestamp()
                    events.append((stamp,match[2],match[3]))
    active, started, finished = set(), set(), set()
    malformed, peak, seconds = False, 0, 0.0
    end = steady_start+budget
    previous = steady_start
    # 同一毫秒保持日志原顺序；同线程重复/缺失生命周期不能证明完整负载。
    for stamp,kind,name in sorted(events,key=lambda event:event[0]):
        boundary = min(max(stamp,steady_start),end)
        if len(active)>=target:
            seconds += max(0,boundary-previous)
        previous = boundary
        if kind=="started":
            malformed |= name in started
            started.add(name); active.add(name)
        else:
            malformed |= name not in active or name in finished
            finished.add(name); active.discard(name)
        peak=max(peak,len(active))
    if len(active)>=target:
        seconds += max(0,end-previous)
    return {"source":"JMeter INFO Thread started/finished", "timezone":"UTC+08:00",
            "complete":bool(started) and started==finished and not active and not malformed,
            "startedThreads":len(started),"finishedThreads":len(finished),"actualPeak":peak,
            "steadyStartEpoch":steady_start,"steadyEndEpoch":end,"expectedSteadySeconds":budget,
            "fullTargetSeconds":round(seconds,6),"fullTargetTimeFraction":seconds/budget if budget>0 else 0}


def evaluate(meta):
    """先判实验有效性，再判零错误与逐label延迟；INVALID不能作为服务容量上界。"""
    reasons = []
    coverage = meta.get("threadCoverage", {})
    overall = meta.get("overall", {})
    if meta.get("nativeGroupStartEpoch") is None:
        reasons.append("NATIVE_GROUP_START_MISSING")
    if meta.get("exitCode", 0) or meta.get("guardStop") or any(meta.get("toolErrors", {}).values()):
        reasons.append("INJECTOR_OR_PROCESS_ERROR")
    if not overall.get("samples"):
        reasons.append("NO_SAMPLES")
    if meta.get("correctnessAssertionsFailed",0):
        reasons.append("DATA_OR_SCRIPT_ASSERTION_FAILURE")
    activity = meta.get("threadActivity",{})
    if not activity.get("complete") or activity.get("actualPeak",0)<meta["threads"]:
        reasons.append("INCOMPLETE_THREAD_LIFECYCLE_OR_INSUFFICIENT_PEAK")
    if activity.get("fullTargetTimeFraction",0)<.95:
        reasons.append("INSUFFICIENT_TARGET_ACTIVE_TIME")
    limits = {"search": (500,1000), "detail": (200,500), "rank": (200,500), "keyword": (200,500),
              "auth": (200,500), "login": (500,1000), "favorite": (500,1000)}
    labels = meta.get("labels", {})
    expected = {"search","detail","rank","keyword"} if meta["scenario"] == "mixed" else {meta["scenario"]}
    if not expected.issubset({label.split('.')[0] for label in labels}):
        reasons.append("MISSING_SCENARIO_LABELS")
    checks = {}
    for label, row in labels.items():
        limit = limits.get(label.split('.')[0])
        if not limit or row.get("p95Ms") is None or row.get("p99Ms") is None:
            reasons.append("UNKNOWN_LABEL_OR_MISSING_PERCENTILE")
            continue
        checks[label] = {"p95LimitMs":limit[0], "p99LimitMs":limit[1],
                         "passed":row["p95Ms"] <= limit[0] and row["p99Ms"] <= limit[1]}
    if reasons:
        return {"status":"INVALID", "reasons":sorted(set(reasons)), "labelChecks":checks}
    failed = bool(overall.get("failures")) or any(not row["passed"] for row in checks.values())
    return {"status":"FAIL" if failed else "PASS", "reasons":["HTTP_OR_BUSINESS_FAILURE"] if overall.get("failures")
            else ["LATENCY_SLO"] if failed else [], "labelChecks":checks}


class RunFailed(RuntimeError):
    def __init__(self, meta):
        self.meta = meta
        super().__init__("运行评价未通过：" + meta["evaluation"]["status"] + " " + meta["name"])


def validate_manifest(context, evidence):
    """发压前绑定批次、API、SQL目标与实时分类；不信任复制来的完成标记。"""
    manifest = read_json(Path(evidence) / "manifest.json")
    if (manifest.get("status") != "COMPLETE" or manifest.get("runId") != context["runId"]
            or manifest.get("apiBase") != context["apiBase"]
            or manifest.get("mysqlTarget") != mysql_target(context)):
        raise ValueError("manifest 批次/API/SQL目标绑定不完整或不匹配，禁止发压")
    verify_category_binding(context, manifest["categories"])
    return manifest


def available_memory_fraction():
    """只读系统可用物理内存，不依赖额外监控包。"""
    if os.name == "nt":
        class MemoryStatus(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong),
                        ("total", ctypes.c_ulonglong), ("available", ctypes.c_ulonglong),
                        ("pageTotal", ctypes.c_ulonglong), ("pageAvailable", ctypes.c_ulonglong),
                        ("virtualTotal", ctypes.c_ulonglong), ("virtualAvailable", ctypes.c_ulonglong),
                        ("extendedAvailable", ctypes.c_ulonglong)]
        status = MemoryStatus()
        status.length = ctypes.sizeof(status)
        if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            return status.available / status.total
    elif Path("/proc/meminfo").exists():
        values = {line.split(":", 1)[0]: int(line.split()[1])
                  for line in Path("/proc/meminfo").read_text().splitlines()}
        return values["MemAvailable"] / values["MemTotal"]
    return None


def stop_process(process):
    """仅结束本轮持有句柄的 JMeter 进程，超时后再强制结束。"""
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def execute(context_path, jmeter_home, java, name, scenario, threads, duration,
            ramp=0, exclude=0, variant="default", think=0, plan=None,
            min_disk_gib=10, memory_threshold=.10):
    """所有运行证据留在 context.evidenceDir，工具和机器路径均由参数传入。"""
    context_path = Path(context_path).resolve()
    context = load_context(context_path)
    evidence = Path(context["evidenceDir"])
    evidence.mkdir(parents=True, exist_ok=True)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,100}", name):
        raise ValueError("name 只允许安全字母数字、点、下划线和横线")
    if threads < 1 or duration < 1 or ramp < 0 or exclude < 0 or exclude >= duration or ramp >= duration or think < 0:
        raise ValueError("线程/时间参数不合法，稳态窗口必须为正")
    if exclude < ramp:
        raise ValueError("exclude 不得小于 ramp，避免将升压样本混入正式统计")
    if min_disk_gib < 1 or not 0 < memory_threshold < 1:
        raise ValueError("资源保护门槛不合法")
    manifest_file = evidence / "manifest.json"
    validate_manifest(context, evidence)
    java_binary = shutil.which(java) or (str(Path(java).resolve()) if Path(java).is_file() else None)
    jmeter_jar = Path(jmeter_home).resolve() / "bin" / "ApacheJMeter.jar"
    if not java_binary or not jmeter_jar.is_file():
        raise ValueError("缺少 Java 可执行文件或 JMeter JAR")
    result_path = evidence / (name + ".jtl")
    log_path = evidence / (name + ".log")
    console_path = evidence / (name + ".console")
    meta_path = evidence / (name + ".meta.json")
    summary_path = evidence / (name + ".summary.json")
    if any(path.exists() for path in (result_path, log_path, console_path, meta_path, summary_path)):
        raise FileExistsError("本轮名称已有证据文件，拒绝覆盖：" + name)
    plan_path = Path(plan).resolve() if plan else evidence / "generic.jmx"
    if not plan_path.exists():
        generate(plan_path)
    arguments = [java_binary, "-Xms128m", "-Xmx512m", "-XX:+UseG1GC", "-Djava.awt.headless=true", "-Duser.timezone=Asia/Shanghai",
                 "-jar", str(jmeter_jar), "-n", "-t", str(plan_path),
                 "-Jscenario=" + scenario, "-Jthreads=" + str(threads), "-Jduration=" + str(duration),
                 "-Jramp=" + str(ramp), "-Jmanifest=" + str(manifest_file), "-Jcontext=" + str(context_path),
                 "-JsearchVariant=" + variant, "-JthinkMs=" + str(think),
                 "-l", str(result_path), "-j", str(log_path),
                 "-Jjmeter.save.saveservice.output_format=csv", "-Jjmeter.save.saveservice.response_data=false",
                 "-Jjmeter.save.saveservice.thread_counts=true",
                 "-Jjmeter.save.saveservice.response_data.on_error=false", "-Jjmeter.save.saveservice.samplerData=false",
                 "-Jjmeter.save.saveservice.requestHeaders=false", "-Jjmeter.save.saveservice.responseHeaders=false",
                 "-Jjmeter.save.saveservice.autoflush=true"]
    started = time.time()
    meta = {"name": name, "scenario": scenario, "threads": threads, "duration": duration,
            "ramp": ramp, "excludedSeconds": exclude, "variant": variant, "thinkMs": think,
            "started": started, "planSha256": hashlib.sha256(plan_path.read_bytes()).hexdigest(),
            "minDiskGiB": min_disk_gib, "memoryThreshold": memory_threshold}
    process = None
    guard_since = None
    reason = None
    runner_error = None
    try:
        # 前置资源失败也保留完整INVALID summary，但绝不启动JMeter。
        if shutil.disk_usage(evidence).free / 1024 ** 3 < min_disk_gib:
            reason = "PREFLIGHT_DISK_GUARD"
            raise ValueError("启动前磁盘不足安全余量，禁止发压")
        memory = available_memory_fraction()
        if memory is not None and memory < memory_threshold:
            reason = "PREFLIGHT_MEMORY_GUARD"
            raise ValueError("启动前可用物理内存不足，禁止发压")
        with console_path.open("x", encoding="utf-8") as console:
            process = subprocess.Popen(arguments, cwd=evidence, stdout=console, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            meta["pid"] = process.pid
            write_json(meta_path, meta)
            write_json(evidence / "active-jmeter.json", {"jmeter": process.pid, "name": name})
            print(json.dumps({"event": "START", **meta}), flush=True)
            while process.poll() is None:
                time.sleep(10)
                elapsed = time.time() - started
                write_json(evidence / "progress.json", {"name": name, "elapsedSeconds": round(elapsed),
                                                        "plannedSeconds": duration})
                if elapsed > duration + 120:
                    reason = "PROCESS_TIMEOUT"
                elif shutil.disk_usage(evidence).free / (1024 ** 3) < min_disk_gib:
                    reason = "DISK_GUARD"
                memory_fraction = available_memory_fraction()
                if memory_fraction is not None and memory_fraction < memory_threshold:
                    guard_since = guard_since or time.time()
                    if time.time() - guard_since >= 60:
                        reason = "MEMORY_GUARD"
                    if memory_fraction < .05:
                        reason = "CRITICAL_MEMORY_GUARD"
                else:
                    guard_since = None
                if reason:
                    stop_process(process)
                    break
                if int(elapsed) // 30 != int(max(0, elapsed - 10)) // 30:
                    print(json.dumps({"event": "PROGRESS", "name": name,
                                      "elapsedSeconds": round(elapsed)}), flush=True)
            process.wait(timeout=30)
    except KeyboardInterrupt:
        reason = "INTERRUPTED"
        if process:
            stop_process(process)
    except Exception as error:
        reason = reason or "RUNNER_ERROR"
        runner_error = type(error).__name__
    finally:
        if process and process.poll() is None:
            stop_process(process)
    group_origin = native_group_start(log_path)
    summary = summarize(result_path, exclude, threads, duration, origin=group_origin) if result_path.exists() else {
        "overall":{"samples":0}, "labels":{}, "threadCoverage":{}, "toolErrors":{}}
    summary["toolErrors"] = dict(collections.Counter(summary["toolErrors"]) + collections.Counter(scan_tool_logs(log_path, console_path)))
    meta.update(exitCode=process.returncode if process else -1, ended=time.time(), guardStop=reason,
                runnerErrorType=runner_error, nativeGroupStartEpoch=group_origin, **summary)
    meta["threadActivity"] = thread_activity(log_path, group_origin+exclude, duration-exclude, threads) if group_origin is not None else {
        "complete":False,"reason":"NATIVE_GROUP_START_MISSING"}
    meta["evaluation"] = evaluate(meta)
    write_json(summary_path, meta)
    print(json.dumps({"event": "FINISHED", "name": name, "overall": summary["overall"],
                      "guardStop": reason}), flush=True)
    if meta["evaluation"]["status"] != "PASS":
        raise RunFailed(meta)
    return meta


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--context", required=True)
    parser.add_argument("--jmeter-home", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--name", required=True)
    parser.add_argument("--scenario", required=True,
                        choices=("search", "detail", "rank", "keyword", "mixed", "auth", "login", "favorite"))
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--duration", type=int, default=20)
    parser.add_argument("--ramp", type=int, default=0)
    parser.add_argument("--exclude", type=int, default=0)
    parser.add_argument("--variant", default="default",
                        choices=("default", "nomatch", "empty", "category", "deep", "createdAt", "downloadCount", "favoriteCount", "hotScore"))
    parser.add_argument("--think", type=int, default=0)
    parser.add_argument("--plan")
    parser.add_argument("--min-disk-gib", type=float, default=10)
    parser.add_argument("--memory-threshold", type=float, default=.10)
    args = parser.parse_args()
    execute(args.context, args.jmeter_home, args.java, args.name, args.scenario,
            args.threads, args.duration, args.ramp, args.exclude, args.variant, args.think,
            args.plan, args.min_disk_gib, args.memory_threshold)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"压测运行中止：{type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
