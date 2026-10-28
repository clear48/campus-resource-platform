"""运行本地 JMeter 并生成无凭据的分场景统计；0退出码不能代替业务断言。"""

import argparse
import collections
import csv
import ctypes
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


def summarize(path, exclude=0):
    """以首个样本开始时间为基准剔除升压/预热窗口，按真实 label 分别统计。"""
    overall = SampleStats()
    labels = collections.defaultdict(SampleStats)
    first = None
    with Path(path).open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            timestamp = int(row["timeStamp"])
            if first is None:
                first = timestamp
            if timestamp < first + exclude * 1000:
                continue
            values = (timestamp, int(row["elapsed"]), row["success"] == "true", row["responseCode"])
            overall.add(*values)
            labels[row["label"]].add(*values)
    window = (overall.start, overall.end) if overall.start is not None else None
    return {"overall": overall.result(), "labels": {name: stats.result(window) for name, stats in labels.items()}}


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
    arguments = [java_binary, "-Xms128m", "-Xmx512m", "-XX:+UseG1GC", "-Djava.awt.headless=true",
                 "-jar", str(jmeter_jar), "-n", "-t", str(plan_path),
                 "-Jscenario=" + scenario, "-Jthreads=" + str(threads), "-Jduration=" + str(duration),
                 "-Jramp=" + str(ramp), "-Jmanifest=" + str(manifest_file), "-Jcontext=" + str(context_path),
                 "-JsearchVariant=" + variant, "-JthinkMs=" + str(think),
                 "-l", str(result_path), "-j", str(log_path),
                 "-Jjmeter.save.saveservice.output_format=csv", "-Jjmeter.save.saveservice.response_data=false",
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
    try:
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
    finally:
        if process and process.poll() is None:
            stop_process(process)
    if not result_path.exists():
        raise RuntimeError("JMeter 未生成 JTL，请检查本轮私有日志：" + name)
    summary = summarize(result_path, exclude)
    meta.update(exitCode=process.returncode, ended=time.time(), guardStop=reason, **summary)
    write_json(summary_path, meta)
    print(json.dumps({"event": "FINISHED", "name": name, "overall": summary["overall"],
                      "guardStop": reason}), flush=True)
    if process.returncode or reason or summary["overall"].get("samples", 0) == 0 or summary["overall"].get("failures", 0):
        raise RuntimeError("本轮未通过业务断言/样本/资源保护校验：" + name)
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
