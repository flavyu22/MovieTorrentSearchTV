"""Regression checks for the actual source packaging filter."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / 'package-source.py'
spec = importlib.util.spec_from_file_location('packager', MODULE_PATH)
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)

class PackagingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old_root = packager.ROOT
        packager.ROOT = Path(self.tmp.name)
    def tearDown(self):
        packager.ROOT = self.old_root
        self.tmp.cleanup()
    def included(self, name):
        path = packager.ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('fixture')
        return packager.included(path)
    def test_both_platform_wrappers_are_preserved(self):
        for name in ['gradlew', 'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar']:
            with self.subTest(name=name): self.assertTrue(self.included(name))
    def test_local_debug_scripts_are_excluded(self):
        for name in ['tvfix.bat', 'tvrun.bat', 'nested/debug.bat']:
            with self.subTest(name=name): self.assertFalse(self.included(name))
    def test_credentials_and_generated_outputs_are_excluded(self):
        for name in ['local.properties', 'release.jks', 'app/build/old.apk', '.gradle/cache', 'old.log']:
            with self.subTest(name=name): self.assertFalse(self.included(name))
    def test_provider_sources_and_fixtures_are_preserved(self):
        for name in ['app/src/main/java/data/scraper/YtsScraper.kt', 'app/src/test/resources/fixture.json']:
            with self.subTest(name=name): self.assertTrue(self.included(name))

if __name__ == '__main__': unittest.main()
