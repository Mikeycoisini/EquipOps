"""A0反例使用真实临时文件；不访问业务数据库。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import subprocess
import sys


SCRIPT = Path(__file__).resolve().parents[1] / "check_a0.py"


class BaselineTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.exists(), "A0核对工具尚未实现")
        spec = importlib.util.spec_from_file_location("check_a0", SCRIPT)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for version in range(1, 16):
            (self.root / f"V{version}__migration.sql").write_text("SELECT 1;\n")

    def test_valid_history_has_numeric_order_and_byte_hash(self):
        import hashlib
        rows = self.module.migrations(self.root)
        self.assertEqual([r["version"] for r in rows], list(range(1, 16)))
        self.assertEqual(rows[0]["sha256"], hashlib.sha256(b"SELECT 1;\n").hexdigest())

    def test_duplicate_audit_v13_is_rejected(self):
        (self.root / "V13__audit.sql").write_text("SELECT 2;")
        with self.assertRaisesRegex(ValueError, "重复"):
            self.module.migrations(self.root)

    def test_missing_historical_version_is_rejected(self):
        (self.root / "V9__migration.sql").unlink()
        with self.assertRaisesRegex(ValueError, "连续"):
            self.module.migrations(self.root)

    def test_leading_zero_version_collision_is_rejected(self):
        (self.root / "V013__audit.sql").write_text("SELECT 2;")
        with self.assertRaisesRegex(ValueError, "重复"):
            self.module.migrations(self.root)

    def test_unrecognised_sql_filename_is_rejected(self):
        (self.root / "audit.sql").write_text("SELECT 2;")
        with self.assertRaisesRegex(ValueError, "文件名"):
            self.module.migrations(self.root)

    def test_future_versions_do_not_hide_missing_baseline(self):
        (self.root / "V16__future.sql").write_text("SELECT 2;")
        self.assertEqual(self.module.migrations(self.root)[-1]["version"], 16)

    def test_symlink_migration_is_rejected(self):
        (self.root / "V16__link.sql").symlink_to(self.root / "V1__migration.sql")
        with self.assertRaisesRegex(ValueError, "符号链接"):
            self.module.migrations(self.root)

    def test_missing_tracked_source_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "缺失"):
            self.module.inventory(self.root, ["missing.java"])

    def test_source_fingerprint_changes_with_bytes(self):
        path = self.root / "source.java"
        path.write_text("before")
        before = self.module.inventory(self.root, [path.name])
        path.write_text("after")
        after = self.module.inventory(self.root, [path.name])
        self.assertNotEqual(before["sha256"], after["sha256"])

    def test_source_symlink_escape_is_rejected(self):
        (self.root / "source.java").symlink_to(Path(__file__))
        with self.assertRaisesRegex(ValueError, "符号链接"):
            self.module.inventory(self.root, ["source.java"])

    def test_remote_report_omits_authentication_parts(self):
        def git(*args):
            return subprocess.run(["git", "-C", str(self.root), *args],
                                  capture_output=True, check=True)
        git("init", "-q")
        (self.root / "entry").write_text("entry")
        git("add", "entry")
        git("-c", "user.name=fixture", "-c", "user.email=fixture@example.test",
            "commit", "-qm", "fixture")
        git("remote", "add", "origin", "https://fixture:dummy@example.test/owner/repo.git?dummy=value#fragment")
        result = self.module.repository(self.root, ["entry"])
        self.assertEqual(result["origin"], "example.test/owner/repo")
        self.assertNotIn("dummy", str(result))
        (self.root / "entry").unlink()
        with self.assertRaisesRegex(ValueError, "缺失"):
            self.module.repository(self.root, ["entry"])

    def test_cli_invalid_repository_exits_nonzero_without_success_json(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--backend", str(self.root),
                                 "--frontend", str(self.root)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertIn("A0核对失败", result.stderr)

    def make_repository(self, origin):
        def git(*args):
            return subprocess.run(["git", "-C", str(self.root), *args],
                                  capture_output=True, check=True)
        git("init", "-q")
        (self.root / "entry").write_text("entry")
        (self.root / "other.java").write_text("class Other {}")
        git("add", "entry", "other.java")
        git("-c", "user.name=fixture", "-c", "user.email=fixture@example.test",
            "commit", "-qm", "fixture")
        git("remote", "add", "origin", origin)
        return git

    def test_staged_deletion_cannot_silently_shrink_baseline(self):
        git = self.make_repository("https://example.test/owner/repo.git")
        git("rm", "other.java")
        with self.assertRaisesRegex(ValueError, "基线文件"):
            self.module.repository(self.root, ["entry"])

    def test_scp_remote_report_omits_query_and_fragment(self):
        self.make_repository("git@example.test:owner/repo.git?token=dummy#fragment")
        result = self.module.repository(self.root, ["entry"])
        self.assertEqual(result["origin"], "example.test/owner/repo")
        self.assertNotIn("dummy", str(result))


if __name__ == "__main__":
    unittest.main()
