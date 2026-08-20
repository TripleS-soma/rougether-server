#!/usr/bin/env python3
"""Rougether Asset Foundry의 로컬 manifest/이미지 품질 게이트."""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

try:
    from PIL import Image, ImageChops
except ImportError as error:  # pragma: no cover - 실행 안내용
    raise SystemExit(
        "Pillow가 필요합니다. README의 uv run 명령으로 실행하세요."
    ) from error


KINDS = {
    "STATIC_FURNITURE",
    "ANIMATED_FURNITURE",
    "CHARACTER_ANIMATION",
    "HOUSE_FRAME",
}
SLUG_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{2,99}$")
ASSET_KEY_PATTERN = re.compile(
    r"^(?:items|characters|house)/[a-z0-9_./-]+\.(?:png|jpe?g|webp)$"
)
ANIMATED_FURNITURE_PATTERN = re.compile(
    r"^items/.+/[a-z0-9_-]+-animated-v\d+\.webp$"
)
CHARACTER_ANIMATION_PATTERN = re.compile(
    r"^characters/[^/]+/animations/(?:idle|pose-cycle|wave)\.webp$"
)
HOUSE_FRAME_PATTERN = re.compile(r"^house/.+frame.+\.png$")


@dataclass(frozen=True)
class Check:
    code: str
    label: str
    result: str
    score: int | None
    required: bool
    message: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "label": self.label,
            "result": self.result,
            "score": self.score,
            "required": self.required,
            "message": self.message,
        }


class ManifestError(ValueError):
    pass


def main() -> int:
    parser = argparse.ArgumentParser(prog="assetctl")
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate_parser = subparsers.add_parser("validate", help="manifest와 출력 이미지를 검증")
    validate_parser.add_argument("manifest", type=Path)
    validate_parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    if args.command == "validate":
        try:
            report = validate_manifest(args.manifest)
        except ManifestError as error:
            print(f"manifest error: {error}", file=sys.stderr)
            return 1
        encoded = json.dumps(report, ensure_ascii=False, indent=2)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(encoded + "\n", encoding="utf-8")
            print(args.report)
        else:
            print(encoded)
        return 0 if report["passed"] else 2
    return 1


def validate_manifest(manifest_path: Path) -> dict[str, Any]:
    manifest_path = manifest_path.expanduser().resolve()
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ManifestError(f"manifest가 없습니다: {manifest_path}") from error
    except json.JSONDecodeError as error:
        raise ManifestError(f"JSON 형식 오류: {error}") from error

    check_manifest_shape(manifest)
    base_dir = manifest_path.parent
    output_path = resolve_path(base_dir, manifest["outputPath"])
    checks: list[Check] = [
        Check("MANIFEST", "Manifest 계약", "PASS", 100, True, "필수 필드와 key 규칙 일치")
    ]
    if not output_path.is_file():
        checks.append(Check("OUTPUT_FILE", "출력 파일", "FAIL", 0, True, str(output_path)))
        return build_report(manifest, output_path, checks)
    checks.append(Check("OUTPUT_FILE", "출력 파일", "PASS", 100, True, str(output_path)))

    try:
        with Image.open(output_path) as image:
            frames = [frame.convert("RGBA") for frame in iter_frames(image)]
            image_info = dict(image.info)
            image_format = image.format or "UNKNOWN"
    except (OSError, SyntaxError) as error:
        checks.append(Check("IMAGE_DECODE", "이미지 디코딩", "FAIL", 0, True, str(error)))
        return build_report(manifest, output_path, checks)

    checks.append(check_format(manifest["kind"], image_format, len(frames)))
    checks.append(check_canvas(manifest, frames))
    checks.append(check_alpha_border(manifest, frames))

    if manifest["kind"] in {"ANIMATED_FURNITURE", "CHARACTER_ANIMATION"}:
        animation = manifest.get("animation", {})
        checks.extend(check_animation(animation, frames, image_info))
        protected_mask_path = animation.get("protectedMaskPath")
        if protected_mask_path:
            checks.append(check_protected_mask(
                resolve_path(base_dir, protected_mask_path), frames))

    if manifest["kind"] == "HOUSE_FRAME":
        checks.append(check_transparent_slots(manifest, frames[0]))

    return build_report(manifest, output_path, checks)


def check_manifest_shape(manifest: dict[str, Any]) -> None:
    required = ["schemaVersion", "name", "slug", "kind", "outputPath", "outputAssetKey"]
    missing = [field for field in required if not manifest.get(field)]
    if missing:
        raise ManifestError(f"필수 필드 누락: {', '.join(missing)}")
    if manifest["schemaVersion"] != 1:
        raise ManifestError("지원하는 schemaVersion은 1입니다")
    if manifest["kind"] not in KINDS:
        raise ManifestError(f"지원하지 않는 kind: {manifest['kind']}")
    if not SLUG_PATTERN.fullmatch(manifest["slug"]):
        raise ManifestError("slug는 소문자·숫자·하이픈 3자 이상이어야 합니다")
    output_key = manifest["outputAssetKey"]
    if not ASSET_KEY_PATTERN.fullmatch(output_key) or ".." in output_key:
        raise ManifestError("outputAssetKey 형식이 올바르지 않습니다")
    kind = manifest["kind"]
    if kind == "STATIC_FURNITURE" and "-animated-v" in output_key:
        raise ManifestError("정적 가구 key에는 -animated-vN을 사용할 수 없습니다")
    if kind == "ANIMATED_FURNITURE" and not ANIMATED_FURNITURE_PATTERN.fullmatch(output_key):
        raise ManifestError("동적 가구 key는 items/.../*-animated-vN.webp 규칙이어야 합니다")
    if kind == "CHARACTER_ANIMATION" and not CHARACTER_ANIMATION_PATTERN.fullmatch(output_key):
        raise ManifestError("캐릭터 모션 key 규칙이 올바르지 않습니다")
    if kind == "HOUSE_FRAME" and not HOUSE_FRAME_PATTERN.fullmatch(output_key):
        raise ManifestError("집 프레임 key는 house/ 아래 frame 이름을 포함한 PNG여야 합니다")


def check_format(kind: str, image_format: str, frame_count: int) -> Check:
    expected = {
        "STATIC_FURNITURE": {"PNG", "WEBP", "JPEG"},
        "ANIMATED_FURNITURE": {"WEBP"},
        "CHARACTER_ANIMATION": {"WEBP"},
        "HOUSE_FRAME": {"PNG"},
    }[kind]
    animated_expected = kind in {"ANIMATED_FURNITURE", "CHARACTER_ANIMATION"}
    valid = image_format in expected and (not animated_expected or frame_count > 1)
    detail = f"{image_format} · {frame_count} frame"
    return Check("IMAGE_FORMAT", "포맷·애니메이션", "PASS" if valid else "FAIL",
                 100 if valid else 0, True, detail)


def check_canvas(manifest: dict[str, Any], frames: list[Image.Image]) -> Check:
    canvas = manifest.get("canvas") or {}
    expected = (canvas.get("width"), canvas.get("height"))
    actual = frames[0].size
    same_frames = all(frame.size == actual for frame in frames)
    expected_matches = None not in expected and actual == expected
    valid = same_frames and expected_matches
    return Check(
        "CANVAS", "캔버스 규격", "PASS" if valid else "FAIL", 100 if valid else 0, True,
        f"actual {actual[0]}×{actual[1]} · expected {expected[0]}×{expected[1]} · "
        f"frame size {'fixed' if same_frames else 'drift'}")


def check_alpha_border(manifest: dict[str, Any], frames: list[Image.Image]) -> Check:
    alpha_max = int(manifest.get("alphaBorderMax", 0))
    violations = 0
    for frame in frames:
        alpha = frame.getchannel("A")
        width, height = frame.size
        border = [
            alpha.crop((0, 0, width, 1)),
            alpha.crop((0, height - 1, width, height)),
            alpha.crop((0, 0, 1, height)),
            alpha.crop((width - 1, 0, width, height)),
        ]
        if any((edge.getextrema() or (0, 0))[1] > alpha_max for edge in border):
            violations += 1
    valid = violations == 0
    return Check("ALPHA_BORDER", "투명 테두리", "PASS" if valid else "FAIL",
                 100 if valid else 0, True,
                 f"위반 프레임 {violations}/{len(frames)} · 허용 alpha ≤ {alpha_max}")


def check_animation(animation: dict[str, Any],
                    frames: list[Image.Image],
                    image_info: dict[str, Any]) -> list[Check]:
    checks: list[Check] = []
    expected_frames = int(animation.get("expectedFrames", 0))
    frame_valid = expected_frames > 1 and len(frames) == expected_frames
    checks.append(Check("FRAME_COUNT", "프레임 수", "PASS" if frame_valid else "FAIL",
                        100 if frame_valid else 0, True,
                        f"{len(frames)} / {expected_frames or '미설정'}"))

    require_loop = bool(animation.get("requireInfiniteLoop", True))
    loop_value = image_info.get("loop")
    loop_valid = not require_loop or loop_value == 0
    checks.append(Check("INFINITE_LOOP", "무한 루프", "PASS" if loop_valid else "FAIL",
                        100 if loop_valid else 0, True, f"loop={loop_value}"))

    preview_sizes = animation.get("previewSizes") or [86, 94]
    min_delta = float(animation.get("minVisibleDeltaPercent", 0.5))
    for size in preview_sizes:
        delta = visible_motion_percent(frames, int(size))
        valid = delta >= min_delta
        checks.append(Check(
            f"RN_{int(size)}PX_MOTION", f"{int(size)}px 움직임 가시성",
            "PASS" if valid else "FAIL", score_motion(delta, min_delta), True,
            f"premultiplied visible delta {delta:.2f}% · minimum {min_delta:.2f}%"))
    return checks


def check_protected_mask(mask_path: Path, frames: list[Image.Image]) -> Check:
    if not mask_path.is_file():
        return Check("PROTECTED_MASK", "보호 영역 불변", "FAIL", 0, True,
                     f"mask가 없습니다: {mask_path}")
    with Image.open(mask_path) as mask_image:
        mask = mask_image.convert("L")
    if mask.size != frames[0].size:
        return Check("PROTECTED_MASK", "보호 영역 불변", "FAIL", 0, True,
                     f"mask {mask.size} / frame {frames[0].size}")
    base = frames[0]
    changed = 0
    black = Image.new("RGBA", base.size, (0, 0, 0, 0))
    for frame in frames[1:]:
        diff = ImageChops.difference(base, frame)
        protected_diff = Image.composite(diff, black, mask)
        if protected_diff.getbbox() is not None:
            changed += 1
    valid = changed == 0
    return Check("PROTECTED_MASK", "보호 영역 불변", "PASS" if valid else "FAIL",
                 100 if valid else 0, True, f"변경 프레임 {changed}/{max(0, len(frames)-1)}")


def check_transparent_slots(manifest: dict[str, Any], frame: Image.Image) -> Check:
    slots = manifest.get("transparentSlots") or []
    if not slots:
        return Check("HOUSE_SLOTS", "방 슬롯 투명도", "FAIL", 0, True,
                     "transparentSlots가 없습니다")
    alpha = frame.getchannel("A")
    violations = []
    for index, slot in enumerate(slots):
        try:
            x = require_plain_int(slot["x"])
            y = require_plain_int(slot["y"])
            width = require_plain_int(slot["width"])
            height = require_plain_int(slot["height"])
        except (KeyError, TypeError, ValueError) as error:
            raise ManifestError(f"transparentSlots[{index}] 형식 오류") from error
        if x < 0 or y < 0 or width <= 0 or height <= 0:
            raise ManifestError(f"transparentSlots[{index}] 좌표와 크기를 확인하세요")
        rect = (x, y, x + width, y + height)
        if rect[2] > frame.width or rect[3] > frame.height:
            raise ManifestError(f"transparentSlots[{index}]가 캔버스를 벗어납니다")
        if (alpha.crop(rect).getextrema() or (0, 0))[1] > 0:
            violations.append(index + 1)
    valid = not violations
    return Check("HOUSE_SLOTS", "방 슬롯 투명도", "PASS" if valid else "FAIL",
                 100 if valid else 0, True,
                 "모든 슬롯 alpha 0" if valid else f"불투명 슬롯: {violations}")


def visible_motion_percent(frames: list[Image.Image], size: int) -> float:
    if len(frames) < 2:
        return 0.0
    prepared = [premultiplied(frame.resize((size, size), Image.Resampling.LANCZOS))
                for frame in frames]
    percentages = []
    for previous, current in zip(prepared, prepared[1:] + prepared[:1]):
        diff = ImageChops.difference(previous, current).convert("L")
        changed = sum(diff.point(lambda value: 255 if value > 3 else 0).histogram()[1:])
        percentages.append(changed / (size * size) * 100)
    return sum(percentages) / len(percentages)


def premultiplied(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    red, green, blue, alpha = rgba.split()
    return Image.merge("RGBA", (
        ImageChops.multiply(red, alpha),
        ImageChops.multiply(green, alpha),
        ImageChops.multiply(blue, alpha),
        alpha,
    ))


def score_motion(delta: float, minimum: float) -> int:
    if minimum <= 0:
        return 100
    return max(0, min(100, round(delta / minimum * 80)))


def require_plain_int(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError("정수가 필요합니다")
    return value


def iter_frames(image: Image.Image) -> Iterable[Image.Image]:
    frame_count = getattr(image, "n_frames", 1)
    for index in range(frame_count):
        image.seek(index)
        yield image.copy()


def build_report(manifest: dict[str, Any], output_path: Path, checks: list[Check]) -> dict[str, Any]:
    required = [check for check in checks if check.required]
    passed = bool(required) and all(check.result == "PASS" for check in required)
    scores = [check.score for check in checks if check.score is not None]
    quality_score = round(sum(scores) / len(scores)) if scores else None
    return {
        "schemaVersion": 1,
        "manifestSlug": manifest["slug"],
        "kind": manifest["kind"],
        "outputPath": str(output_path),
        "outputAssetKey": manifest["outputAssetKey"],
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "passed": passed,
        "qualityScore": quality_score,
        "checks": [check.to_dict() for check in checks],
    }


def resolve_path(base_dir: Path, raw_path: str) -> Path:
    path = Path(raw_path).expanduser()
    return path.resolve() if path.is_absolute() else (base_dir / path).resolve()


if __name__ == "__main__":
    raise SystemExit(main())
