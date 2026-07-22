#!/usr/bin/env python3
"""Crop, tune, pad, and resize a transparent PNG for Figma insertion."""

from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageEnhance


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--figma-out", type=Path)
    parser.add_argument("--brightness", type=float, default=1.0)
    parser.add_argument("--contrast", type=float, default=1.0)
    parser.add_argument("--saturation", type=float, default=1.0)
    parser.add_argument("--pad", type=int, default=80)
    parser.add_argument("--max-dim", type=int, default=700)
    args = parser.parse_args()

    image = Image.open(args.input).convert("RGBA")
    alpha = image.getchannel("A")
    bounding_box = alpha.getbbox()
    if not bounding_box:
        raise SystemExit("Input has no visible alpha content.")

    left, top, right, bottom = bounding_box
    crop_pad = 32
    cropped = image.crop(
        (
            max(0, left - crop_pad),
            max(0, top - crop_pad),
            min(image.width, right + crop_pad),
            min(image.height, bottom + crop_pad),
        )
    )

    red, green, blue, alpha = cropped.split()
    rgb = Image.merge("RGB", (red, green, blue))
    rgb = ImageEnhance.Brightness(rgb).enhance(args.brightness)
    rgb = ImageEnhance.Contrast(rgb).enhance(args.contrast)
    rgb = ImageEnhance.Color(rgb).enhance(args.saturation)
    tuned = Image.merge("RGBA", (*rgb.split(), alpha))

    output = Image.new(
        "RGBA",
        (tuned.width + args.pad * 2, tuned.height + args.pad * 2),
        (0, 0, 0, 0),
    )
    output.alpha_composite(tuned, (args.pad, args.pad))
    output.save(args.out)

    if args.figma_out:
        scale = min(1.0, args.max_dim / max(output.size))
        figma = output.resize(
            (round(output.width * scale), round(output.height * scale)),
            Image.Resampling.LANCZOS,
        )
        figma.save(args.figma_out)
        print(f"wrote {args.figma_out} size={figma.size}")

    print(f"wrote {args.out} size={output.size}")
    print(
        "corner_alpha",
        [
            output.getpixel((0, 0))[3],
            output.getpixel((output.width - 1, 0))[3],
            output.getpixel((0, output.height - 1))[3],
            output.getpixel((output.width - 1, output.height - 1))[3],
        ],
    )


if __name__ == "__main__":
    main()
