import pathlib, tempfile, unittest
import brand_visibility_gate as g


def tree(files):
    d = pathlib.Path(tempfile.mkdtemp())
    for name, text in files.items():
        p = d / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
    return d


class Gate(unittest.TestCase):
    def test_repo_is_clean(self):
        self.assertEqual(g.scan(), [])

    def test_new_migration_fails(self):
        d = tree({"db/migrations/V3000__x.sql": "USING (tenant_id = ANY (app_visible_tenant_ids()))",
                  "backend/src/main/java/a/B.java": ""})
        self.assertEqual(g.scan(d), ["db/migrations/V3000__x.sql"])

    def test_baseline_migration_and_allowed_java_pass(self):
        d = tree({"db/migrations/V211__x.sql": "app_visible_tenant_ids",
                  "backend/src/main/java/a/TenantContext.java": "visible_tenant_ids"})
        self.assertEqual(g.scan(d), [])

    def test_other_java_fails(self):
        d = tree({"db/migrations/V1__x.sql": "", "backend/src/main/java/a/Foo.java": "set_config('app.accessible_tenant_ids')"})
        self.assertEqual(g.scan(d), ["backend/src/main/java/a/Foo.java"])


if __name__ == "__main__":
    unittest.main()
