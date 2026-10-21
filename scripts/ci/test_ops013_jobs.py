#!/usr/bin/env python3
"""OPS-013 static checks. Run from the repo root: python3 -m unittest scripts/ci/test_ops013_jobs.py
1. Every @Scheduled method in backend main code carries @SchedulerLock(name=...) with a unique name, unless the lines
   above it say "no @SchedulerLock on purpose" (per-replica caches/gauges).
2. The 3-nights purge alarm exists in the cell's alerting config with the right evaluation window and the SNS action."""
import glob, re, unittest

SRC = "backend/src/main/java"
TF = "infra/terraform/modules/cell/aws/monitoring.tf"


def scheduled_methods():
    out = []
    for f in glob.glob(f"{SRC}/**/*.java", recursive=True):
        lines = open(f, encoding="utf-8").read().splitlines()
        for i, l in enumerate(lines):
            if l.strip().startswith("@Scheduled("):
                j = i
                while not re.search(r"\b(public|void|int)\b.*\(", lines[j]):
                    j += 1
                out.append((f, i + 1, "\n".join(lines[max(0, i - 3):j + 1])))
    return out


class ShedLockCoverage(unittest.TestCase):
    def test_every_scheduled_job_is_locked_or_marked_per_replica(self):
        names, missing = [], []
        for f, line, block in scheduled_methods():
            m = re.search(r'@SchedulerLock\(name = "([\w-]+)"', block)
            if m:
                names.append(m.group(1))
            elif "no @SchedulerLock on purpose" not in block:
                missing.append(f"{f}:{line}")
        self.assertEqual(missing, [])
        self.assertEqual(len(names), len(set(names)), f"duplicate lock names {names}")
        self.assertGreaterEqual(len(names), 12)

    def test_detector_flags_an_unlocked_job(self):
        self.assertIsNone(re.search(r'@SchedulerLock\(name = "', '@Scheduled(cron = "0 0 3 * * *")\npublic void x() {'))


class PurgeAlarm(unittest.TestCase):
    def test_three_consecutive_failed_days_page_on_call(self):
        tf = open(TF).read()
        m = re.search(r'resource "aws_cloudwatch_metric_alarm" "purge_job_failing" \{(.*?)\n\}', tf, re.S)
        self.assertIsNotNone(m)
        body = dict(re.findall(r"^\s*(\w+)\s*=\s*(.+?)\s*(?:#.*)?$", m.group(1), re.M))
        self.assertEqual(body["metric_name"], '"job_failures_total"')
        self.assertEqual(body["dimensions"], "{ job = each.key }")
        self.assertEqual((body["period"], body["evaluation_periods"], body["datapoints_to_alarm"]), ("86400", "3", "3"))
        self.assertEqual((body["threshold"], body["comparison_operator"]), ("0", '"GreaterThanThreshold"'))
        self.assertIn("aws_sns_topic.alerts.arn", body["alarm_actions"])
        jobs = re.search(r"purge_jobs\s*=\s*\[(.*?)\]", tf).group(1)
        self.assertEqual(sorted(re.findall(r'"([\w-]+)"', jobs)), ["login-attempts-purge", "messages-purge", "outbox-purge", "sessions-purge"])
        for job in re.findall(r'"([\w-]+)"', jobs):  # each alarmed job really is a locked job of that name
            self.assertTrue(any(f'@SchedulerLock(name = "{job}"' in b for _, _, b in scheduled_methods()), job)


if __name__ == "__main__":
    unittest.main()
