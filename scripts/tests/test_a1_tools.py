import importlib.util
from pathlib import Path
import unittest
import os
import subprocess
import tempfile


def load(name):
    path = Path(__file__).resolve().parents[1] / (name + ".py")
    if not path.exists():
        return None
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class A1ToolsTests(unittest.TestCase):
    def test_smoke_refuses_remote_destinations_before_sending_credentials(self):
        module = load("a1_smoke")
        self.assertIsNotNone(module, "HTTP smoke尚未实现")
        for url in ["https://example.test", "http://127.0.0.1.evil.test", "http://user:pw@127.0.0.1:8082"]:
            with self.assertRaises(ValueError):
                module.local_base(url)
        self.assertEqual(module.local_base("http://127.0.0.1:8082/"), "http://127.0.0.1:8082")

    def test_public_scan_catches_tokens_without_returning_secret_text(self):
        module = load("check_public")
        self.assertIsNotNone(module, "公开内容检查尚未实现")
        token = "ghp_" + "x" * 36
        findings = module.findings("src/example.java", "token=" + token)
        self.assertTrue(findings)
        self.assertNotIn(token, str(findings))

    def test_public_scan_blocks_private_files_but_allows_examples(self):
        module = load("check_public")
        self.assertIsNotNone(module, "公开内容检查尚未实现")
        self.assertTrue(module.findings(".env.local", ""))
        self.assertTrue(module.findings("keys/id_rsa", ""))
        self.assertFalse(module.findings(".env.example", "DB_PASSWORD=\n"))

    def test_verify_preflight_failure_prevents_spring_tests(self):
        script = Path(__file__).resolve().parents[1] / "a1_verify.sh"
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            (root / "scripts").mkdir()
            (root / "bin").mkdir()
            (root / "scripts/a1_verify.sh").write_text(script.read_text())
            (root / "mvnw").write_text('echo "maven:$*" >> "$TRACE"\nmkdir -p target\necho fixture > target/a1-classpath.txt\n')
            java = root / "bin/java"
            java.write_text('#!/bin/sh\necho "java:$*" >> "$TRACE"\nexit 1\n')
            java.chmod(0o700)
            env = os.environ.copy()
            for key in list(env):
                if key.startswith("SPRING_"): env.pop(key)
            env.update(PATH=str(root / "bin") + os.pathsep + env["PATH"], TRACE=str(root / "trace"),
                       DB_URL="jdbc:mysql://127.0.0.1/equipops_a1_fixture", DB_USER="fixture",
                       DB_PASSWORD="dummy", A1_DB_CONFIRM="equipops_a1_fixture", A1_TEST_PASSWORD="x" * 24,
                       REDIS_SESSION_NAMESPACE="equipops:a1:fixture", INVENTORY_EXPORT_ROOT=str(root / "artifacts/a1/exports"))
            result = subprocess.run(["bash", "scripts/a1_verify.sh"], cwd=root, env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            trace = (root / "trace").read_text()
            self.assertIn("--check-target", trace)
            self.assertNotIn("-Dtest=!OperationsHttpTest", trace)

    def test_verify_rejects_missing_isolation_before_build(self):
        script = Path(__file__).resolve().parents[1] / "a1_verify.sh"
        with tempfile.TemporaryDirectory() as value:
            root = Path(value)
            (root / "scripts").mkdir()
            (root / "scripts/a1_verify.sh").write_text(script.read_text())
            (root / "mvnw").write_text('touch build-started\nexit 1\n')
            env = os.environ.copy()
            for key in list(env):
                if key.startswith("SPRING_"): env.pop(key)
            env.update(DB_URL="jdbc:mysql://127.0.0.1/equipops_a1_fixture", DB_USER="fixture",
                       DB_PASSWORD="dummy", A1_DB_CONFIRM="equipops_a1_fixture", A1_TEST_PASSWORD="x" * 24)
            env.pop("REDIS_SESSION_NAMESPACE", None)
            env.pop("INVENTORY_EXPORT_ROOT", None)
            result = subprocess.run(["bash", "scripts/a1_verify.sh"], cwd=root, env=env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((root / "build-started").exists())


if __name__ == "__main__":
    unittest.main()
