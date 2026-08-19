import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw


MODULE_PATH = Path(__file__).resolve().parents[1] / "assetctl.py"
SPEC = importlib.util.spec_from_file_location("assetctl", MODULE_PATH)
assetctl = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = assetctl
SPEC.loader.exec_module(assetctl)


class AssetCtlTest(unittest.TestCase):

    def test_animated_webp_passes_visible_motion_gates(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            output = root / "planet-animated-v1.webp"
            frames = []
            for offset in (8, 13, 18, 13):
                frame = Image.new("RGBA", (40, 40), (0, 0, 0, 0))
                ImageDraw.Draw(frame).ellipse((offset, 12, offset + 10, 22), fill=(245, 180, 80, 255))
                frames.append(frame)
            frames[0].save(output, save_all=True, append_images=frames[1:], duration=100, loop=0,
                           lossless=True, disposal=2)
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps({
                "schemaVersion": 1,
                "name": "Planet",
                "slug": "planet-test",
                "kind": "ANIMATED_FURNITURE",
                "outputPath": output.name,
                "outputAssetKey": "items/cozy-space/furniture/planet-animated-v1.webp",
                "canvas": {"width": 40, "height": 40},
                "animation": {
                    "expectedFrames": 4,
                    "previewSizes": [86],
                    "minVisibleDeltaPercent": 0.1
                }
            }), encoding="utf-8")

            report = assetctl.validate_manifest(manifest_path)

            self.assertTrue(report["passed"])
            results = {check["code"]: check["result"] for check in report["checks"]}
            self.assertEqual("PASS", results["FRAME_COUNT"])
            self.assertEqual("PASS", results["RN_86PX_MOTION"])

    def test_house_slot_must_be_transparent(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            output = root / "house-frame-v1.png"
            image = Image.new("RGBA", (20, 20), (140, 90, 40, 255))
            image.save(output)
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps({
                "schemaVersion": 1,
                "name": "House",
                "slug": "house-frame-test",
                "kind": "HOUSE_FRAME",
                "outputPath": output.name,
                "outputAssetKey": "house/test/house-frame-v1.png",
                "canvas": {"width": 20, "height": 20},
                "alphaBorderMax": 255,
                "transparentSlots": [{"x": 4, "y": 4, "width": 8, "height": 8}]
            }), encoding="utf-8")

            report = assetctl.validate_manifest(manifest_path)

            self.assertFalse(report["passed"])
            results = {check["code"]: check["result"] for check in report["checks"]}
            self.assertEqual("FAIL", results["HOUSE_SLOTS"])

    def test_house_slot_cannot_escape_canvas(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            output = root / "house-frame-v1.png"
            Image.new("RGBA", (20, 20), (0, 0, 0, 0)).save(output)
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps({
                "schemaVersion": 1,
                "name": "House",
                "slug": "house-frame-bounds",
                "kind": "HOUSE_FRAME",
                "outputPath": output.name,
                "outputAssetKey": "house/test/house-frame-v1.png",
                "canvas": {"width": 20, "height": 20},
                "transparentSlots": [{"x": 18, "y": 4, "width": 8, "height": 8}]
            }), encoding="utf-8")

            with self.assertRaisesRegex(assetctl.ManifestError, "캔버스를 벗어납니다"):
                assetctl.validate_manifest(manifest_path)

    def test_static_furniture_cannot_use_animated_version_key(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps({
                "schemaVersion": 1,
                "name": "Static",
                "slug": "static-key-check",
                "kind": "STATIC_FURNITURE",
                "outputPath": "static.png",
                "outputAssetKey": "items/test/furniture/static-animated-v1.webp",
                "canvas": {"width": 20, "height": 20}
            }), encoding="utf-8")

            with self.assertRaisesRegex(assetctl.ManifestError, "정적 가구 key"):
                assetctl.validate_manifest(manifest_path)


if __name__ == "__main__":
    unittest.main()
