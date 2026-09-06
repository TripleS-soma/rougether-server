package com.triples.rougether.userapi.furniture.service;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// JPEG APP1의 orientation 한 값만 읽음. 모든 offset은 해당 APP1 segment 내부로 제한함.
final class PhotoOrientation {
    private PhotoOrientation() { }

    static int read(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216) return 1;
        int offset = 2;
        while (offset + 4 <= bytes.length && (bytes[offset] & 255) == 255) {
            int marker = bytes[offset + 1] & 255;
            if (marker == 218 || marker == 217) break;
            int length = ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
            if (length < 2 || offset + 2L + length > bytes.length) break;
            int start = offset + 4;
            if (marker == 225 && length >= 16 && bytes[start] == 'E' && bytes[start + 1] == 'x'
                    && bytes[start + 2] == 'i' && bytes[start + 3] == 'f'
                    && bytes[start + 4] == 0 && bytes[start + 5] == 0) {
                try {
                    ByteBuffer tiff = ByteBuffer.wrap(bytes, start + 6, length - 8).slice();
                    if (tiff.get(0) == 'I' && tiff.get(1) == 'I') tiff.order(ByteOrder.LITTLE_ENDIAN);
                    else if (tiff.get(0) != 'M' || tiff.get(1) != 'M') return 1;
                    if (Short.toUnsignedInt(tiff.getShort(2)) != 42) return 1;
                    long directory = Integer.toUnsignedLong(tiff.getInt(4));
                    if (directory > tiff.limit() - 2L) return 1;
                    int count = Short.toUnsignedInt(tiff.getShort((int) directory));
                    if (directory + 2L + 12L * count > tiff.limit()) return 1;
                    for (int i = 0; i < count; i++) {
                        int entry = (int) directory + 2 + 12 * i;
                        if (Short.toUnsignedInt(tiff.getShort(entry)) == 0x0112
                                && tiff.getShort(entry + 2) == 3 && tiff.getInt(entry + 4) == 1) {
                            int orientation = Short.toUnsignedInt(tiff.getShort(entry + 8));
                            return orientation >= 1 && orientation <= 8 ? orientation : 1;
                        }
                    }
                } catch (IndexOutOfBoundsException e) { return 1; }
            }
            offset += 2 + length;
        }
        return 1;
    }

    static BufferedImage apply(BufferedImage image, int orientation) {
        if (orientation <= 1 || orientation > 8) return image;
        int width = image.getWidth(), height = image.getHeight();
        boolean swap = orientation >= 5;
        var rotated = new BufferedImage(swap ? height : width, swap ? width : height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int dx = x, dy = y;
                switch (orientation) {
                    case 2 -> dx = width - 1 - x;
                    case 3 -> { dx = width - 1 - x; dy = height - 1 - y; }
                    case 4 -> dy = height - 1 - y;
                    case 5 -> { dx = y; dy = x; }
                    case 6 -> { dx = height - 1 - y; dy = x; }
                    case 7 -> { dx = height - 1 - y; dy = width - 1 - x; }
                    case 8 -> { dx = y; dy = width - 1 - x; }
                    default -> { }
                }
                rotated.setRGB(dx, dy, image.getRGB(x, y));
            }
        }
        return rotated;
    }
}
