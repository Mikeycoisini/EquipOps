from contextlib import closing
import importlib.util
from pathlib import Path
import tempfile
import unittest
from uuid import UUID


spec = importlib.util.spec_from_file_location(
    "availability_probe", Path(__file__).resolve().parents[1] / "availability_probe.py")
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ProbeStreamTest(unittest.TestCase):
    def test_stream_identity_is_stable_and_unique_per_database(self):
        with tempfile.TemporaryDirectory() as directory:
            identities = []
            for name in ("first", "second"):
                path = Path(directory) / name
                for run in range(2):
                    with closing(probe.open_state(path)) as database, database:
                        probe.read_or_initialize(database, 9093)
                        identity = database.execute("SELECT stream_id FROM probe_identity").fetchone()[0]
                        self.assertEqual(identity, str(UUID(identity)))
                        if run == 0:
                            identities.append(identity)
                        else:
                            self.assertEqual(identities[-1], identity)
            self.assertNotEqual(*identities)
