package com.triples.rougether.preprocessing;

import app.photofox.vipsffm.*;
import app.photofox.vipsffm.enums.*;
import java.lang.foreign.*;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class VipsPhotoPreprocessor {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final byte[] PNG_MAGIC = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    // Lambda 실행 환경에서 한 번 초기화함. 이미지별 Arena와 엔진 전역 수명을 분리함.
    private static class Runtime {
        static {
            String nativePath = System.getenv("VIPS_NATIVE_PATH");
            if (nativePath != null && !nativePath.isBlank()) {
                String absolute = Path.of(nativePath).toAbsolutePath().toString();
                for (String lib : List.of("vips", "glib", "gobject")) {
                    System.setProperty("vipsffm.libpath." + lib + ".override", absolute);
                }
            }
            Vips.init();
            VipsHelper.cache_set_max_mem(50L * 1024 * 1024);
            VipsHelper.cache_set_max(100);
            VipsHelper.cache_set_max_files(20);
        }
        static void initialize() { }
    }

    public PreparedPhoto prepare(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
        boolean jpeg = bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
        boolean png = bytes.length >= 8 && Arrays.equals(bytes, 0, 8, PNG_MAGIC, 0, 8);
        if (!jpeg && !png) throw invalid();
        Runtime.initialize();
        try (var processing = Arena.ofConfined()) {
            var blob = VBlob.newFromBytes(processing, bytes);
            var failOn = VipsOption.Enum("fail_on", VipsFailOn.FAIL_ON_WARNING);
            var header = jpeg ? VImage.jpegloadBuffer(processing, blob, failOn)
                    : VImage.pngloadBuffer(processing, blob, failOn);
            int w = header.getWidth(), h = header.getHeight();
            if (w < 32 || h < 32 || w > 8192 || h > 8192 || (long) w * h > 32_000_000) throw invalid();
            Integer pages = header.getInt("n-pages");
            if (pages != null && pages > 1) throw invalid();
            int factor = Math.max(1, (Math.max(w, h) + 1535) / 1536);
            Integer orientation = header.getInt("orientation");
            boolean swap = orientation != null && orientation >= 5 && orientation <= 8;
            int width = ((swap ? h : w) + factor - 1) / factor;
            int height = ((swap ? w : h) + factor - 1) / factor;
            double shrink = Math.min((double) (swap ? h : w) / width, (double) (swap ? w : h) / height);
            int jpegShrink = shrink >= 8 ? 8 : shrink >= 4 ? 4 : shrink >= 2 ? 2 : 1;
            if (jpegShrink > 1 && (int) shrink == jpegShrink) jpegShrink /= 2;
            var image = jpeg && jpegShrink > 1
                    ? VImage.jpegloadBuffer(processing, blob, failOn, VipsOption.Int("shrink", jpegShrink),
                        VipsOption.Enum("access", VipsAccess.ACCESS_SEQUENTIAL)) : header;
            var oriented = image.autorot();
            var resized = oriented.resize((double) width / oriented.getWidth(),
                    VipsOption.Double("vscale", (double) height / oriented.getHeight()),
                    VipsOption.Enum("kernel", VipsKernel.KERNEL_LANCZOS3));
            var encoded = resized.pngsaveBuffer(VipsOption.Int("compression", 6),
                    VipsOption.Int("filter", 8), VipsOption.Int("keep", 0), VipsOption.Int("bitdepth", 8),
                    VipsOption.Boolean("interlace", false), VipsOption.Boolean("palette", false));
            long size = encoded.byteSize();
            if (size < 1 || size > MAX_BYTES) throw invalid();
            // 1.9.8의 VBlob 포인터 scope 버그를 복사 경계에서 보정함. deallocator를 중복 등록하지 않음.
            // upstream #236의 수정 버전 배포 전까지 원본 포인터/버퍼를 호출자에게 노출하지 않음.
            var scoped = encoded.getUnsafeDataAddress().reinterpret(size, processing, null);
            return PreparedPhoto.copyOf(scoped, width, height);
        } catch (VipsError e) { throw new IllegalArgumentException("유효하지 않은 이미지", e); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("JPEG/PNG 크기 또는 형식 오류"); }
}
