"""S9 QA PLT-037: the CI log-PHI scan must be green on the merged tree, red on seeded bad PRs."""
import importlib.util, pathlib, subprocess, sys, tempfile, unittest
root = pathlib.Path(__file__).parents[2]
spec = importlib.util.spec_from_file_location('s', root / 'scripts/log-phi-scan.py')
s = importlib.util.module_from_spec(spec); spec.loader.exec_module(s)

def scan(src):
    with tempfile.TemporaryDirectory() as d:
        (pathlib.Path(d) / 'A.java').write_text(src)
        return s.scan([d])

class T(unittest.TestCase):
    def test_merged_tree_is_clean(self):  # this is exactly the CI step
        self.assertEqual(s.scan([str(root / 'backend/src/main/java')]), [])
    def test_cli_exit_code_on_tree(self):
        r = subprocess.run([sys.executable, str(root / 'scripts/log-phi-scan.py'), str(root / 'backend/src/main/java')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout)
    def test_each_named_word_fails(self):
        for w in ['phone', 'otp', 'code', 'token', 'reason', 'note', 'name', 'diagnosis']:
            self.assertTrue(scan(f'class A {{ void f() {{ log.warn("x {{}}", {w}); }} }}'), w)
            self.assertTrue(scan(f'class A {{ void f() {{ logger.info("x {{}}", req.get{w.capitalize()}()); }} }}'), w)
    def test_clean_statement_passes(self):
        self.assertFalse(scan('class A { void f() { log.info("x {} {}", staffId, tenantId); } }'))
    def test_known_gap_uppercase_logger_and_fluent_api(self):
        # informational: documents bypasses; fails if the scan ever starts catching them (then delete this test)
        self.assertFalse(scan('class A { void f() { LOG.info("x {}", phone); } }'), 'LOG.* is not scanned (gap)')
        self.assertFalse(scan('class A { void f() { log.atInfo().log("x {}", phone); } }'), 'fluent API is not scanned (gap)')

unittest.main()
