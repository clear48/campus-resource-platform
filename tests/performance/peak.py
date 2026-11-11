"""从100线程搜索本地SLO并发边界；发压端INVALID不参与服务容量二分。"""

import argparse
import re
from pathlib import Path
import sys
import time

from common import load_context, write_json
from run import execute, RunFailed


def search_boundary(probe, max_threads=1600, precision=25, relative_precision=.10):
    """可注入纯探测函数，便于模拟上界通过、确认退化和发压端失败而不实际发压。"""
    if max_threads < 100 or precision < 1 or not 0 < relative_precision <= 1:
        raise ValueError("max-threads至少100，精度须为正数")
    attempts, passing = [], set()
    formal_guidance = False

    def attempt(threads, formal=False, repetition=1):
        if len(attempts) >= 100:
            raise RuntimeError("INCONCLUSIVE_NON_MONOTONIC")
        result = probe(threads, formal, repetition)
        status = result["status"]
        if status not in ("PASS", "FAIL", "INVALID"):
            raise ValueError("探测返回未知评价")
        attempts.append({"threads":threads, "formal":formal, "repetition":repetition, **result})
        if status == "INVALID":
            raise RuntimeError("INVALID_OR_CORRECTNESS_REQUIRES_REVIEW")
        if status == "PASS":
            passing.add(threads)
        else:
            passing.discard(threads)
        # 短诊断曾与正式结果冲突后，不再反复将同类诊断失败当上界；正式复核决定搜索方向。
        if status == "FAIL" and not formal and formal_guidance:
            return attempt(threads, True, 1)
        return status

    def confirm(threads):
        for repetition in (1,2,3):
            if attempt(threads, True, repetition) != "PASS":
                return False
        return True

    low, high = None, None
    try:
        level = 100
        if attempt(level) == "PASS":
            low = level
        else:
            high = level
            # 100失败时先取得真实的正数合格下界，绝不把0当作已测试容量。
            while level > 1:
                level = max(1, level // 2)
                if attempt(level) == "PASS":
                    low = level
                    break
                high = level
            if low is None:
                return {"status":"NO_PASSING_LEVEL", "attempts":attempts, "confirmedPass":None,
                        "note":"1线程也未通过合法SLO，未取得可持续下界"}
        while True:
            if high is None:
                while low < max_threads:
                    level = min(max_threads, low * 2)
                    if attempt(level) == "FAIL":
                        high = level
                        break
                    low = level
            if high is not None:
                while high-low > max(precision, int(low * relative_precision)):
                    middle = (low+high)//2
                    if attempt(middle) == "PASS":
                        low = middle
                    else:
                        high = middle
            if not confirm(low):
                high = low
                lower = [value for value in passing if value < low]
                if lower:
                    low = max(lower)
                else:
                    level, low = high, None
                    while level > 1:
                        level = max(1,level//2)
                        if attempt(level) == "PASS":
                            low = level
                            break
                    if low is None:
                        return {"status":"NO_CONFIRMED_PASS", "attempts":attempts, "confirmedPass":None}
                continue
            if high is None:
                return {"status":"LOWER_BOUND_ONLY", "confirmedPass":low, "confirmedFail":None,
                        "attempts":attempts, "note":"达到探测上限仍通过，未测出系统并发峰值"}
            if attempt(high, True) == "FAIL":
                return {"status":"CONFIRMED_BRACKET", "confirmedPass":low, "confirmedFail":high,
                        "precisionThreads":high-low, "attempts":attempts,
                        "note":"下界三次正式通过、相邻上界一次正式失败；此环境与SLO下的区间"}
            # 原诊断上界正式通过：继续扩展，不能沿用旧失败或把探测上限当峰值。
            low, high = high, None
            formal_guidance = True
    except RuntimeError as error:
        return {"status":"INVALID" if str(error)=="INVALID_OR_CORRECTNESS_REQUIRES_REVIEW" else "INCONCLUSIVE",
                "reason":str(error), "attempts":attempts, "confirmedPass":None,
                "invalidReasons":attempts[-1].get("reasons",[]) if attempts else [],
                "diagnosticLower":low, "note":"实验无效、资源能力或正确性异常待核查，不能作为服务端失败参与二分"}


def run_peak(args):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,60}", args.name):
        raise ValueError("搜索名称只允许安全字母数字、点、横线和下划线")
    context = load_context(args.context)
    evidence = Path(context["evidenceDir"])
    output = evidence / (args.name + ".peak.json")
    if output.exists():
        raise FileExistsError("已有边界搜索报告，拒绝覆盖")
    sequence = 0
    progress = {"scenario":args.scenario, "status":"RUNNING", "started":time.time(),
                "maxThreads":args.max_threads, "precisionThreads":args.precision, "thinkMs":0, "attempts":[]}

    def probe(threads, formal, repetition):
        nonlocal sequence
        sequence += 1
        duration, ramp = (args.confirm_duration,30) if formal else (args.diagnostic_duration,15)
        name = f"{args.name}-{'formal' if formal else 'diag'}-c{threads}-r{repetition}-a{sequence}"
        try:
            meta = execute(args.context,args.jmeter_home,args.java,name,args.scenario,threads,duration,
                           ramp=ramp,exclude=ramp,think=0,plan=args.plan)
        except RunFailed as error:
            meta = error.meta
        except Exception as error:
            # 前置目标/工具/资源检查失败不归因于服务端。仅记异常类型，不复制含路径的异常文本。
            result = {"status":"INVALID", "name":name,"reasons":["PREFLIGHT_"+type(error).__name__]}
            progress["attempts"].append({"threads":threads,"formal":formal,**result})
            write_json(output,progress)
            return result
        result = {"name":name, "status":meta["evaluation"]["status"], "reasons":meta["evaluation"]["reasons"],
                  "overall":meta["overall"], "labels":meta["labels"], "threadCoverage":meta["threadCoverage"],
                  "planSha256":meta["planSha256"], "labelChecks":meta["evaluation"]["labelChecks"]}
        progress["attempts"].append({"threads":threads,"formal":formal,"repetition":repetition,**result})
        write_json(output,progress)
        return result

    result = search_boundary(probe,args.max_threads,args.precision)
    progress.update(result,ended=time.time())
    write_json(output,progress)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--context",required=True)
    parser.add_argument("--jmeter-home",required=True)
    parser.add_argument("--java",default="java")
    parser.add_argument("--name",default="concurrency-peak")
    parser.add_argument("--scenario",default="mixed",choices=("mixed","search","detail","rank","keyword","auth","login"))
    parser.add_argument("--max-threads",type=int,default=1600)
    parser.add_argument("--precision",type=int,default=25)
    parser.add_argument("--diagnostic-duration",type=int,default=110)
    parser.add_argument("--confirm-duration",type=int,default=340)
    parser.add_argument("--plan")
    args = parser.parse_args()
    if args.diagnostic_duration <= 15 or args.confirm_duration <= 30:
        parser.error("诊断/确认时长必须大于升压排除窗口")
    result = run_peak(args)
    print(result["status"],flush=True)
    return 0 if result["status"] in ("CONFIRMED_BRACKET","LOWER_BOUND_ONLY") else 1


if __name__ == "__main__":
    sys.exit(main())
