import importlib.util, pathlib, tempfile, unittest
spec = importlib.util.spec_from_file_location('s', pathlib.Path(__file__).parents[1] / 'log-phi-scan.py')
s = importlib.util.module_from_spec(spec); spec.loader.exec_module(s)

def scan(src):
    with tempfile.TemporaryDirectory() as d:
        (pathlib.Path(d) / 'A.java').write_text(src)
        return s.scan([d])

class T(unittest.TestCase):
    def test_bad_args_fail(self):
        for a in ['phone', 'otp', 'code', 'token', 'req.reason()', 'note', 'patient.name()', 'diagnosis', 'staff.mobilePhone()']:
            self.assertTrue(scan(f'class A {{ void f() {{ log.info("x {{}}", {a}); }} }}'), a)
    def test_multiline_and_warn(self):
        self.assertTrue(scan('class A { void f() { log.warn("x {} {}",\n id,\n otp); } }'))
    def test_ids_pass(self):
        self.assertFalse(scan('class A { void f() { log.info("code token reason {} {}", id, note.id()); } }'))
    def test_marker_suppresses(self):
        self.assertFalse(scan('class A { void f() { log.info("x {}", token); // log-phi-ok: queue number\n } }'))

unittest.main()
