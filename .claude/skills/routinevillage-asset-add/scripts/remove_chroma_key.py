#!/usr/bin/env python3
"""Remove a flat chroma-key border from an image and preserve soft edges."""

from __future__ import annotations

import argparse
from pathlib import Path
from statistics import median

from PIL import Image

Color = tuple[int, int, int]


def parse_hex_color(raw: str) -> Color:
    value = raw.strip().removeprefix("#")
    if len(value) != 6:
        raise argparse.ArgumentTypeError("key color must be a 6-digit RGB hex value")
    try:
        return tuple(int(value[index : index + 2], 16) for index in (0, 2, 4))  # type: ignore[return-value]
    except ValueError as error:
        raise argparse.ArgumentTypeError("key color must be a 6-digit RGB hex value") from error


def border_key(image: Image.Image) -> Color:
    width, height = image.size
    pixels = image.load()
    band = max(1, min(width, height, 6))
    step = max(1, min(width, height) // 256)
    samples: list[Color] = []

    for x in range(0, width, step):
        for y in range(band):
            samples.append(pixels[x, y][:3])
            samples.append(pixels[x, height - 1 - y][:3])
    for y in range(0, height, step):
        for x in range(band):
            samples.append(pixels[x, y][:3])
            samples.append(pixels[width - 1 - x, y][:3])

    return tuple(
        round(median(sample[channel] for sample in samples))
        for channel in range(3)
    )  # type: ignore[return-value]


def smoothstep(value: float) -> float:
    bounded = max(0.0, min(1.0, value))
    return bounded * bounded * (3.0 - 2.0 * bounded)


def channel_distance(color: Color, key: Color) -> int:
    return max(abs(color[index] - key[index]) for index in range(3))


def looks_key_colored(color: Color, key: Color, distance: int) -> bool:
    if distance <= 32:
        return True

    key_channel = max(range(3), key=key.__getitem__)
    if key[key_channel] < 128:
        return False

    other_channels = [
        color[index]
        for index in range(3)
        if index != key_channel
    ]
    return color[key_channel] - max(other_channels) >= 16


def despill(color: Color, key: Color, alpha: int) -> Color:
    if alpha >= 252:
        return color

    key_channel = max(range(3), key=key.__getitem__)
    if key[key_channel] < 128:
        return color

    channels = list(color)
    other_channels = [
        channels[index]
        for index in range(3)
        if index != key_channel
    ]
    channels[key_channel] = min(channels[key_channel], max(other_channels))
    return tuple(channels)  # type: ignore[return-value]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--key-color", type=parse_hex_color, default="#00ff00")
    parser.add_argument("--auto-key", choices=["none", "border"], default="none")
    parser.add_argument("--transparent-threshold", type=float, default=12.0)
    parser.add_argument("--opaque-threshold", type=float, default=96.0)
    parser.add_argument("--despill", action="store_true")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    if args.transparent_threshold >= args.opaque_threshold:
        raise SystemExit("--transparent-threshold must be lower than --opaque-threshold")
    if not args.input.exists():
        raise SystemExit(f"Input image not found: {args.input}")
    if args.out.exists() and not args.force:
        raise SystemExit(f"Output already exists: {args.out} (use --force to overwrite)")
    if args.out.suffix.lower() != ".png":
        raise SystemExit("--out must use .png to preserve transparency")

    image = Image.open(args.input).convert("RGBA")
    key = border_key(image) if args.auto_key == "border" else args.key_color
    pixels = image.load()
    transparent = 0
    partial = 0

    for y in range(image.height):
        for x in range(image.width):
            red, green, blue, original_alpha = pixels[x, y]
            color = (red, green, blue)
            distance = channel_distance(color, key)
            key_colored = looks_key_colored(color, key, distance)

            if not key_colored:
                alpha = original_alpha
            elif distance <= args.transparent_threshold:
                alpha = 0
            elif distance >= args.opaque_threshold:
                alpha = original_alpha
            else:
                ratio = (
                    (distance - args.transparent_threshold)
                    / (args.opaque_threshold - args.transparent_threshold)
                )
                alpha = round(original_alpha * smoothstep(ratio))

            if alpha == 0:
                pixels[x, y] = (0, 0, 0, 0)
                transparent += 1
                continue

            output_color = (
                despill(color, key, alpha)
                if args.despill and key_colored
                else color
            )
            pixels[x, y] = (*output_color, alpha)
            if alpha < 255:
                partial += 1

    args.out.parent.mkdir(parents=True, exist_ok=True)
    image.save(args.out)
    print(f"wrote {args.out} size={image.size}")
    print(f"key_color=#{key[0]:02X}{key[1]:02X}{key[2]:02X}")
    print(f"transparent_pixels={transparent} partial_pixels={partial}")


if __name__ == "__main__":
    main()
