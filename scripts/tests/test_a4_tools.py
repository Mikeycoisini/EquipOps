import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


class A4ToolsTests(unittest.TestCase):
    def test_remote_base_is_rejected_before_credentials_or_database_fixture(self):
        root = Path(__file__).resolve().parents[1]
        sys.path.insert(0, str(root))
        try:
            spec = importlib.util.spec_from_file_location('a4_smoke_checked', root / 'a4_smoke.py')
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            with patch.object(module, 'fixture') as fixture:
                with self.assertRaises(ValueError):
                    module.run('https://example.test', 'isolated-test-password-placeholder')
                fixture.assert_not_called()
        finally:
            sys.path.pop(0)
