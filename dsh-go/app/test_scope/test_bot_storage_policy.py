"""Source policy checks; these do not replace merged-manifest or device tests."""
# SPDX-License-Identifier: AGPL-3.0-only
import re
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[1] / "android/app"
A = "{http://schemas.android.com/apk/res/android}"


class BotStoragePolicyTest(unittest.TestCase):
    def test_file_provider_has_no_shared_storage_root(self):
        paths = ET.parse(APP / "src/main/res/xml/dsh_file_paths.xml").getroot()
        self.assertFalse(paths.findall("external-path"))
        self.assertTrue(any(p.tag == "files-path" and p.get("path") == "workspace/" for p in paths))

    def test_current_target_and_no_legacy_lint_exemption(self):
        gradle = (APP / "build.gradle.kts").read_text()
        self.assertGreaterEqual(int(re.search(r"targetSdk\s*=\s*(\d+)", gradle)[1]), 36)
        self.assertNotIn('disable += "ExpiredTargetSdkVersion"', gradle)

    def test_key_prefs_are_excluded_from_every_backup_mode(self):
        application = ET.parse(APP / "src/main/AndroidManifest.xml").getroot().find("application")
        self.assertEqual(application.get(A + "allowBackup"), "false")
        for attribute, modes in [("fullBackupContent", [None]), ("dataExtractionRules", ["cloud-backup", "device-transfer"])]:
            ref = application.get(A + attribute)
            self.assertTrue(ref.startswith("@xml/"))
            rules = ET.parse(APP / f"src/main/res/xml/{ref[5:]}.xml").getroot()
            for mode in modes:
                section = rules if mode is None else rules.find(mode)
                self.assertIsNotNone(section, mode)
                self.assertTrue(any(e.get("domain") == "sharedpref" and e.get("path") == "minimax_bot_provider.xml" for e in section.findall("exclude")), mode)


if __name__ == "__main__":
    unittest.main()
