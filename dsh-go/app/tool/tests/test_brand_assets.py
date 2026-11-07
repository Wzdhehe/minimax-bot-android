"""Brand-exclusion and Android resource contracts (no Flutter SDK needed)."""
import re
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[2]
REPO = APP.parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"

class BrandAssetsTest(unittest.TestCase):
    def test_excluded_art_is_absent(self):
        for name in ["assets/brand", "assets/pets", "assets/whale", "assets/star",
                     "tool/pet_src", "tool/whale_src", "tool/make_pet_pack.py",
                     "tool/make_brand_mark.py", "tool/make_whale_sprites.py",
                     "lib/ui/shell/brand_glyph.g.dart"]:
            with self.subTest(path=name):
                self.assertFalse((APP / name).exists(), name)
        self.assertFalse((APP.parent / "docs/assets").exists())

    def test_declared_asset_paths_exist(self):
        for path in re.findall(r"^\s+(?:- |asset: )(assets/[^\s]+)", (APP / "pubspec.yaml").read_text(), re.M):
            with self.subTest(path=path):
                self.assertTrue((APP / path).exists(), path)

    def test_no_excluded_art_in_app_bundle_or_loaders(self):
        for path in [APP / "pubspec.yaml", *(APP / "lib").rglob("*.dart")]:
            for forbidden in ["assets/brand/", "assets/whale/", "assets/star/", "brand_glyph.g.dart",
                              "assets/pets/deepy-whale", "assets/pets/whale-girl", "StarPromptDialog.asset"]:
                with self.subTest(file=str(path.relative_to(APP)), token=forbidden):
                    self.assertNotIn(forbidden, path.read_text())

    def test_no_builtin_pet(self):
        text = (APP / "lib/pets/pet_prefs.dart").read_text()
        self.assertIn("const builtInPets = <String>[];", text)
        self.assertIn("const defaultPetId = noPetId;", text)

    def test_placeholder_foregrounds_are_geometric(self):
        from PIL import Image
        res = APP / "android/app/src/main/res"
        files = list(res.glob("mipmap-*/ic_launcher*foreground.png"))
        self.assertEqual(len(files), 20)
        for path in files:
            with self.subTest(path=path.name):
                image = Image.open(path).convert("RGBA")
                self.assertEqual(image.width, image.height)
                # M stems are solid; the bottom middle and outer safe zone clear.
                def alpha(x, y):
                    return image.getpixel((round(x * image.width / 108), round(y * image.height / 108)))[3]
                self.assertEqual(alpha(36, 60), 255)
                self.assertEqual(alpha(72, 60), 255)
                self.assertEqual(alpha(54, 70), 0)
                self.assertEqual(alpha(10, 10), 0)
                self.assertEqual(len([color for count, color in image.getcolors(image.width * image.height) if color[3]]), 1)

    def test_readmes_have_no_removed_images(self):
        for name in ["README.md", "README.en.md"]:
            self.assertNotIn("docs/assets/", (APP.parent / name).read_text())

    def test_numeric_pet_fixtures_have_no_art_or_brand_metadata(self):
        import json
        root = APP / "test/pets/fixtures"
        self.assertEqual(len(list(root.glob("*.json"))), 2)
        self.assertFalse(any(root.glob("*.png")))
        for path in root.glob("*.json"):
            data = json.loads(path.read_text())
            self.assertTrue(data["id"].startswith("test-rectangle-"))
            self.assertFalse({"source", "author", "license"} & data.keys())
            bounds = {row.split()[0]: list(map(int, row.split()[1:])) for row in path.with_suffix(".bounds").read_text().splitlines()}
            self.assertEqual(bounds.keys(), data["animations"].keys())
            for name, anim in data["animations"].items():
                l, t, r, b = bounds[name]
                self.assertTrue(0 <= l < r <= anim["frameW"])
                self.assertTrue(0 <= t < b <= anim["frameH"])

    def test_android_icon_references_resolve(self):
        res = APP / "android/app/src/main/res"
        for xml in [APP / "android/app/src/main/AndroidManifest.xml", *res.rglob("ic_launcher*.xml")]:
            root = ET.parse(xml).getroot()
            for node in root.iter():
                for value in node.attrib.values():
                    if value.startswith(("@mipmap/ic_launcher", "@drawable/ic_launcher")):
                        kind, name = value[1:].split("/")
                        self.assertTrue(any(res.glob(f"{kind}*/{name}.*")), (xml, value))
        for path in res.glob("mipmap-anydpi-v26/ic_launcher*.xml"):
            self.assertEqual(ET.parse(path).getroot().tag, "adaptive-icon")

if __name__ == "__main__":
    unittest.main()
