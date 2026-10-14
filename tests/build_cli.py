"""A typo in a release key path must not replace the app's signing identity."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class SigningGuardTest(unittest.TestCase):
    def test_missing_update_key_aborts_before_build_and_preserves_output(self):
        with tempfile.TemporaryDirectory(prefix='attendance-signing-test-') as temp:
            folder = Path(temp)
            key = folder / 'missing.keystore'
            output = folder / 'existing.apk'
            original = b'existing APK must survive a signing-key typo'
            output.write_bytes(original)
            result = subprocess.run(
                [sys.executable, str(ROOT / 'scripts' / 'build_apk.py'),
                 '--sdk', str(folder / 'missing-sdk'), '--keystore', str(key),
                 '--output', str(output)],
                text=True, capture_output=True, timeout=30,
            )
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertIn('specified signing key does not exist', result.stderr)
            self.assertFalse(key.exists())
            self.assertEqual(output.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
