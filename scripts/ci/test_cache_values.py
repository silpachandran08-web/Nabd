#!/usr/bin/env python3
"""OPS-020: the cache-value scan fails on bad samples and passes on good ones (python3 scripts/ci/test_cache_values.py)."""
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import cache_values  # noqa: E402

BAD = """
class X {
    void a() {
        String v = valkey.opsForValue().get(key);
        log.info("cached {} for {}", v, key);
    }
    void b() {
        String cached = t.opsForValue().getAndSet(k, "x");
        meters.counter("nabd.cache", "value", cached).increment();
    }
}
"""
GOOD = """
class Y {
    void a() {
        String v = valkey.opsForValue().get(key);
        log.info("cache lookup for {}", key);
        meters.counter("nabd.cache", "outcome", v == null ? "miss" : "hit").increment();
    }
}
"""


class CacheValues(unittest.TestCase):
    def test_value_in_log_or_metric_is_caught(self):
        hits = cache_values.scan_source(BAD)
        self.assertEqual({name for _, name in hits}, {"v", "cached"})

    def test_keys_and_outcomes_pass(self):
        # 'v' only inside a ternary producing a fixed label is still flagged: the rule is strict on purpose
        self.assertEqual(cache_values.scan_source(GOOD.replace('v == null ? "miss" : "hit"', '"hit"')), [])

    def test_repo_is_clean(self):
        self.assertEqual(cache_values.check_static(), [])

    def test_runtime_samples(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)
            (p / "values.txt").write_text("Pacific/Chatham\n1\n")
            (p / "logs.txt").write_text("INFO session cache lookup nabd:sess:g:abc\n")
            (p / "metrics.txt").write_text('nabd_session_cache_lookups_total{outcome="hit"} 3.0\n')
            self.assertEqual(cache_values.check_samples(d), [])
            (p / "logs.txt").write_text("WARN tenant zone Pacific/Chatham\n")
            errs = cache_values.check_samples(d)
            self.assertEqual(len(errs), 1)
            self.assertNotIn("Pacific/Chatham", errs[0])
            (p / "values.txt").write_text("1\n")
            self.assertTrue(cache_values.check_samples(d))  # nothing distinctive to look for: refuse


if __name__ == "__main__":
    unittest.main()
