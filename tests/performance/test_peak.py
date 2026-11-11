"""评价与边界搜索回归；全部使用合成JTL和探测函数，不启动服务或发送HTTP。"""

import csv
from datetime import datetime, timedelta, timezone
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import peak
import run


class EvaluationTests(unittest.TestCase):
    def meta(self,scenario="search"):
        return {"threads":100,"duration":110,"excludedSeconds":15,"scenario":scenario,"exitCode":0,"guardStop":None,
                "overall":{"samples":1000,"failures":0},"labels":{scenario:{"p95Ms":200,"p99Ms":400}},
                "threadCoverage":{"fieldsAvailable":True,"peakGrpThreads":100,"peakAllThreads":100,
                                  "fullTargetSampleFraction":1,"effectiveSteadySeconds":95,
                                  "fullTargetTimeFraction":1,"expectedSteadyBuckets":95},
                "threadActivity":{"complete":True,"actualPeak":100,"fullTargetTimeFraction":1},"toolErrors":{}}

    def test_latency_and_business_failures_are_fail_when_load_valid(self):
        meta=self.meta()
        self.assertEqual("PASS",run.evaluate(meta)["status"])
        meta["labels"]["search"]["p95Ms"]=501
        self.assertEqual("FAIL",run.evaluate(meta)["status"])
        meta=self.meta(); meta["overall"]["failures"]=1
        self.assertEqual("FAIL",run.evaluate(meta)["status"])

    def test_injector_or_inadequate_window_is_invalid_before_slo(self):
        for change in ({"actualPeak":90},{"fullTargetTimeFraction":.9},{"complete":False}):
            meta=self.meta(); meta["threadActivity"].update(change); meta["labels"]["search"]["p95Ms"]=999
            with self.subTest(change=change): self.assertEqual("INVALID",run.evaluate(meta)["status"])
        for kind in ("BIND","OOM","SCRIPT"):
            meta=self.meta();meta["toolErrors"]={kind:1}
            self.assertEqual("INVALID",run.evaluate(meta)["status"])

    def test_mixed_checks_each_label_with_its_own_slo(self):
        meta=self.meta("mixed")
        meta["labels"]={label:{"p95Ms":150,"p99Ms":450} for label in ("search","detail","rank","keyword")}
        self.assertEqual("PASS",run.evaluate(meta)["status"])
        meta["labels"]["detail"]["p95Ms"]=201
        self.assertEqual("FAIL",run.evaluate(meta)["status"])
        del meta["labels"]["keyword"]
        self.assertEqual("INVALID",run.evaluate(meta)["status"])

    def test_completion_order_does_not_define_first_start(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"samples.jtl"
            with path.open("w",newline="",encoding="utf-8") as stream:
                writer=csv.DictWriter(stream,fieldnames=("timeStamp","elapsed","label","success","responseCode","grpThreads","allThreads"))
                writer.writeheader()
                for timestamp,elapsed,threads in ((2000,1,100),(1000,1500,20),(3000,100,100)):
                    writer.writerow(dict(timeStamp=timestamp,elapsed=elapsed,label="search",success="true",responseCode="200",grpThreads=threads,allThreads=threads))
            summary=run.summarize(path,1,100)
            self.assertEqual(2,summary["overall"]["samples"])
            self.assertEqual(1,summary["threadCoverage"]["fullTargetSampleFraction"])
            self.assertAlmostEqual(1.1,summary["threadCoverage"]["effectiveSteadySeconds"])

    def test_many_fast_full_samples_do_not_hide_later_low_or_empty_seconds(self):
        for late_threads in (10,100):
            with self.subTest(late_threads=late_threads),tempfile.TemporaryDirectory() as temporary:
                path=Path(temporary)/"coverage.jtl"
                with path.open("w",newline="",encoding="utf-8") as stream:
                    writer=csv.DictWriter(stream,fieldnames=("timeStamp","elapsed","label","success","responseCode","grpThreads","allThreads"))
                    writer.writeheader()
                    # 99%以上样本在首秒满并发，随后空桶/低并发；最后一条长尾把maxEnd拖满100秒。
                    for index in range(1000):
                        writer.writerow(dict(timeStamp=1000+index%100,elapsed=1,label="search",success="true",responseCode="200",grpThreads=100,allThreads=100))
                    writer.writerow(dict(timeStamp=91000,elapsed=10000,label="search",success="true",responseCode="200",grpThreads=late_threads,allThreads=late_threads))
                summary=run.summarize(path,0,100,100)
                self.assertGreater(summary["threadCoverage"]["fullTargetSampleFraction"],.95)
                self.assertEqual(100,summary["threadCoverage"]["effectiveSteadySeconds"])
                self.assertLess(summary["threadCoverage"]["fullTargetTimeFraction"],.03)
                meta=self.meta();meta.update(summary,duration=100,excludedSeconds=0)
                meta["threadActivity"]["fullTargetTimeFraction"] = .01
                self.assertEqual("INVALID",run.evaluate(meta)["status"])

    def test_mixed_thread_bucket_is_bad_and_final_five_percent_exit_is_allowed(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"coverage.jtl"
            with path.open("w",newline="",encoding="utf-8") as stream:
                writer=csv.DictWriter(stream,fieldnames=("timeStamp","elapsed","label","success","responseCode","grpThreads","allThreads"))
                writer.writeheader()
                for second in range(100):
                    threads=100 if second<96 else 50
                    writer.writerow(dict(timeStamp=1000+second*1000,elapsed=1000 if second==99 else 1,label="search",success="true",responseCode="200",grpThreads=threads,allThreads=threads))
                writer.writerow(dict(timeStamp=1500,elapsed=1,label="search",success="true",responseCode="200",grpThreads=50,allThreads=50))
            summary=run.summarize(path,0,100,100)
            coverage=summary["threadCoverage"]
            self.assertEqual(100,coverage["expectedSteadyBuckets"])
            self.assertEqual(95,coverage["fullTargetSeconds"])
            self.assertEqual(.95,coverage["fullTargetTimeFraction"])
            meta=self.meta();meta.update(summary,duration=100,excludedSeconds=0)
            self.assertEqual("PASS",run.evaluate(meta)["status"])

    def write_lifecycle(self,path,threads=100,finish_seconds=110,omit_finish=False):
        base=datetime(2026,11,11,12,0,0,tzinfo=timezone(timedelta(hours=8)))
        lines=[]
        for index in range(threads):
            lines.append(f"{base.strftime('%Y-%m-%d %H:%M:%S')},000 INFO o.a.j.t.JMeterThread: Thread started: load 1-{index}\n")
        end=base+timedelta(seconds=finish_seconds)
        for index in range(threads-int(omit_finish)):
            lines.append(f"{end.strftime('%Y-%m-%d %H:%M:%S')},000 INFO o.a.j.t.JMeterThread: Thread finished: load 1-{index}\n")
        path.write_text(''.join(lines),encoding="utf-8")
        return base.timestamp()

    def test_100_active_threads_waiting_two_seconds_is_legal_slo_fail(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); log=root/"run.log"
            base=self.write_lifecycle(log)
            path=root/"run.jtl"
            with path.open("w",newline="",encoding="utf-8") as stream:
                writer=csv.DictWriter(stream,fieldnames=("timeStamp","elapsed","label","success","responseCode","grpThreads","allThreads"))
                writer.writeheader()
                for second in range(0,110,2):
                    writer.writerow(dict(timeStamp=int((base+second)*1000),elapsed=2000,label="search",success="true",responseCode="200",grpThreads=100,allThreads=100))
            meta=self.meta();meta.update(run.summarize(path,15,100,110))
            self.assertLess(meta["threadCoverage"]["fullTargetTimeFraction"],.6)
            meta["threadActivity"]=run.thread_activity(log,base+15,95,100)
            self.assertEqual(95,meta["threadActivity"]["fullTargetSeconds"])
            self.assertEqual("FAIL",run.evaluate(meta)["status"])
            self.assertEqual(["LATENCY_SLO"],run.evaluate(meta)["reasons"])

    def test_partial_start_early_finish_or_missing_lifecycle_is_invalid(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"run.log"
            for threads,finish,missing in ((50,110,False),(100,20,False),(100,110,True)):
                with self.subTest(threads=threads,finish=finish,missing=missing):
                    base=self.write_lifecycle(path,threads,finish,missing)
                    meta=self.meta();meta["threadActivity"]=run.thread_activity(path,base+15,95,100)
                    self.assertEqual("INVALID",run.evaluate(meta)["status"])

    def test_preflight_low_memory_never_starts_jmeter_and_keeps_invalid_summary(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary); (root/"bin").mkdir(); (root/"bin"/"ApacheJMeter.jar").write_bytes(b"fake")
            plan=root/"generic.jmx";plan.write_text("test-plan",encoding="utf-8")
            with patch.object(run,"load_context",return_value={"evidenceDir":str(root)}), \
                    patch.object(run,"validate_manifest"), patch.object(run.shutil,"which",return_value="java"), \
                    patch.object(run.shutil,"disk_usage",return_value=type("Disk",(),{"free":11*1024**3})()), \
                    patch.object(run,"available_memory_fraction",return_value=.09), \
                    patch.object(run.subprocess,"Popen") as process, self.assertRaises(run.RunFailed) as failed:
                run.execute(root/"context.json",root,"java","preflight","search",100,110,15,15,plan=plan)
            process.assert_not_called()
            self.assertEqual("INVALID",failed.exception.meta["evaluation"]["status"])
            self.assertTrue((root/"preflight.summary.json").exists())


class BoundaryTests(unittest.TestCase):
    def probe(self,threshold):
        return lambda threads,formal,repetition:{"status":"PASS" if threads<=threshold else "FAIL"}

    def test_expands_bisects_and_confirms_three_passes(self):
        report=peak.search_boundary(self.probe(275),800)
        self.assertEqual("CONFIRMED_BRACKET",report["status"])
        self.assertEqual((275,300),(report["confirmedPass"],report["confirmedFail"]))
        passes=[a for a in report["attempts"] if a["formal"] and a["threads"]==275 and a["status"]=="PASS"]
        self.assertEqual(3,len(passes))

    def test_100_failure_descends_to_real_positive_lower(self):
        report=peak.search_boundary(self.probe(60),100)
        self.assertEqual("CONFIRMED_BRACKET",report["status"])
        self.assertGreater(report["confirmedPass"],0)
        self.assertEqual([100,50],[a["threads"] for a in report["attempts"][:2]])

    def test_cap_pass_is_only_lower_bound(self):
        report=peak.search_boundary(self.probe(9999),400)
        self.assertEqual("LOWER_BOUND_ONLY",report["status"])
        self.assertIsNone(report["confirmedFail"])
        self.assertEqual(400,report["confirmedPass"])

    def test_invalid_never_becomes_service_failure_bracket(self):
        report=peak.search_boundary(lambda t,f,r:{"status":"PASS" if t==100 else "INVALID"},800)
        self.assertEqual("INVALID",report["status"])
        self.assertIsNone(report["confirmedPass"])
        self.assertEqual(2,len(report["attempts"]))

    def test_formal_fail_moves_candidate_down(self):
        report=peak.search_boundary(lambda t,f,r:{"status":"PASS" if t<=(175 if f else 400) else "FAIL"},800)
        self.assertEqual("CONFIRMED_BRACKET",report["status"])
        self.assertLessEqual(report["confirmedPass"],175)

    def test_formal_upper_pass_reopens_expansion(self):
        report=peak.search_boundary(lambda t,f,r:{"status":"PASS" if t<=(300 if f else 100) else "FAIL"},800)
        self.assertEqual("CONFIRMED_BRACKET",report["status"])
        self.assertLessEqual(report["confirmedPass"],300)
        self.assertGreater(report["confirmedFail"],300)
        self.assertGreater(max(a["threads"] for a in report["attempts"]),200)

    def test_no_passing_single_thread_never_claims_zero_capacity(self):
        report=peak.search_boundary(self.probe(0),100)
        self.assertEqual("NO_PASSING_LEVEL",report["status"])
        self.assertIsNone(report["confirmedPass"])


if __name__=="__main__": unittest.main()
