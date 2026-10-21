"""PLT-019: tenant_params.py counts explicit tenantId parameters on service methods (run: python3 -m unittest scripts/ci/test_tenant_params.py)."""
import json, os, subprocess, sys, tempfile, unittest

SCRIPT = os.path.join(os.path.dirname(__file__), "tenant_params.py")


def run(*args):
    p = subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True)
    return p.returncode, p.stdout, p.stderr


class TenantParams(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        os.makedirs(f"{self.d}/queue"); os.makedirs(f"{self.d}/messaging")
        open(f"{self.d}/queue/QueueService.java", "w").write("""
class QueueService {
    public List<X> list(UUID tenantId, int limit) {
        if (ok(tenantId)) { return repo.list(tenantId); }
    }
    @Transactional
    public X move(UUID tenantId,
                  UUID entryId) throws ApiException {
    }
    private void helper(UUID staffId) {
    }
}""")
        open(f"{self.d}/queue/QueueRepository.java", "w").write("class QueueRepository { List<X> list(UUID tenantId) { } }")
        open(f"{self.d}/messaging/TemplateService.java", "w").write("class TemplateService { public List<X> list() { } }")

    def test_counts_declarations_not_calls_nor_repositories(self):
        code, out, _ = run(self.d)
        self.assertEqual(code, 0)
        r = json.loads(out)
        self.assertEqual(r["total"], 2)
        self.assertEqual(r["by_module"], {"queue": 2, "messaging": 0})

    def test_zero_gate_fails_only_for_a_module_still_taking_tenant_id(self):
        self.assertEqual(run(self.d, "--zero", "messaging")[0], 0)
        code, _, err = run(self.d, "--zero", "messaging", "queue")
        self.assertEqual(code, 1)
        self.assertIn("queue", err)

    def test_repo_tree_messaging_is_migrated(self):
        root = os.path.join(os.path.dirname(__file__), "..", "..", "backend/src/main/java/com/nabd/hms")
        code, out, _ = run(root, "--zero", "messaging")
        self.assertEqual(code, 0, out)
        self.assertGreater(json.loads(out)["total"], 0)


if __name__ == "__main__":
    unittest.main()
