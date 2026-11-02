"""Check that the Bot-only app cannot register phone-control entry points."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

MANIFEST = Path(__file__).resolve().parents[1] / "android/app/src/main/AndroidManifest.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"

class BotManifestTest(unittest.TestCase):
    def test_no_device_control_components_or_permissions(self):
        root = ET.parse(MANIFEST).getroot()
        forbidden = {
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "moe.shizuku.manager.permission.API_V23",
        }
        for permission in root.findall("uses-permission"):
            if permission.get(ANDROID + "name") in forbidden:
                self.assertEqual(permission.get(TOOLS + "node"), "remove")
        app = root.find("application")
        self.assertIsNotNone(app)
        for component in app:
            name = component.get(ANDROID + "name", "")
            if ".phone." in name or name == "rikka.shizuku.ShizukuProvider":
                self.assertEqual(component.get(TOOLS + "node"), "remove", name)

if __name__ == "__main__":
    unittest.main()
