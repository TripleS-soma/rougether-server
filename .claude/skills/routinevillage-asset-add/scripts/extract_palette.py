#!/usr/bin/env python3
"""Extract a compact RGB palette from RoutineVillage-style reference art."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw


def parse_box(raw: str) -> tuple[int, int, int, int]:
    parts = [int(part.strip()) for part in raw.split(",")]
    if len(parts) != 4:
        raise argparse.ArgumentTypeError("crop boxes must be x1,y1,x2,y2")
    return tuple(parts)  # type: ignore[return-value]


def hex_rgb(rgb: tuple[int, int, int]) -> str:
    return "#{:02X}{:02X}{:02X}".format(*rgb)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument(
        "--crop",
        action="append",
        type=parse_box,
        help="Crop box x1,y1,x2,y2. Can be repeated.",
    )
    parser.add_argument("--colors", type=int, default=18)
    parser.add_argument("--swatch", type=Path, help="Optional swatch PNG output path.")
    args = parser.parse_args()

    image = Image.open(args.image).convert("RGBA")
    boxes = args.crop or [(0, 0, image.width, image.height)]
    pixels: list[tuple[int, int, int]] = []

    for box in boxes:
        for red, green, blue, alpha in image.crop(box).getdata():
            if alpha < 128:
                continue
            if red > 245 and green > 245 and blue > 245:
                continue
            if (
                red > 238
                and green > 232
                and blue > 220
                and max(red, green, blue) - min(red, green, blue) < 22
            ):
                continue
            pixels.append((red, green, blue))

    if not pixels:
        raise SystemExit("No non-background pixels found.")

    palette_image = Image.new("RGB", (len(pixels), 1))
    palette_image.putdata(pixels)
    quantized = palette_image.quantize(
        colors=args.colors,
        method=Image.Quantize.MEDIANCUT,
    )
    palette = quantized.getpalette()
    counts = Counter(quantized.getdata())

    dominant: list[tuple[tuple[int, int, int], int]] = []
    print("dominant_palette")
    for index, count in counts.most_common(args.colors):
        rgb = tuple(palette[index * 3 : index * 3 + 3])
        dominant.append((rgb, count))
        print(f"{count:6d} RGB{rgb} {hex_rgb(rgb)}")

    categories: dict[str, list[tuple[int, int, int]]] = {
        "warm_outline": [],
        "screen_grey_dark": [],
        "wood_surface": [],
        "peach_accent": [],
        "green_accent": [],
    }
    for red, green, blue in pixels:
        luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        if luminance < 150:
            greyish = (
                abs(red - green) < 18
                and abs(green - blue) < 18
                and abs(red - blue) < 18
            )
            category = "screen_grey_dark" if greyish else "warm_outline"
            categories[category].append((red, green, blue))
        if (
            red > 210
            and green > 160
            and blue > 105
            and red - green < 85
            and green - blue < 95
        ):
            categories["wood_surface"].append((red, green, blue))
        if (
            red > 210
            and green > 120
            and blue > 110
            and red - green > 35
            and green - blue < 70
        ):
            categories["peach_accent"].append((red, green, blue))
        if (
            145 < red < 215
            and 145 < green < 215
            and 90 < blue < 170
            and abs(red - green) < 35
            and green - blue > 20
        ):
            categories["green_accent"].append((red, green, blue))

    print("\ncategory_averages")
    for name, values in categories.items():
        if not values:
            continue
        average = tuple(
            round(sum(rgb[index] for rgb in values) / len(values))
            for index in range(3)
        )
        print(f"{name:16s} n={len(values):5d} RGB{average} {hex_rgb(average)}")

    if args.swatch:
        swatch = Image.new("RGB", (len(dominant) * 80, 120), "white")
        draw = ImageDraw.Draw(swatch)
        for index, (rgb, _count) in enumerate(dominant):
            x = index * 80
            draw.rectangle([x, 0, x + 79, 79], fill=rgb)
            draw.text((x + 4, 84), hex_rgb(rgb), fill=(0, 0, 0))
        swatch.save(args.swatch)
        print(f"\nsaved {args.swatch}")


if __name__ == "__main__":
    main()
