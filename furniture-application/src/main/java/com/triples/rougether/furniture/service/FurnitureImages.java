package com.triples.rougether.furniture.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.furniture.error.FurnitureGenerationErrorCode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FurnitureImages {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int OUTPUT_SIZE = 1024;

    public record Photo(byte[] png, String digest) { }
    public record Candidate(byte[] png, List<String> failures) {
        public boolean passed() { return failures.isEmpty(); }
    }

    public Photo photo(MultipartFile file, String hint) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalidPhoto();
        try (var stream = file.getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalidPhoto();
            // 확장자·Content-Type을 신뢰하지 않고 디코더와 실제 치수를 검사함.
            BufferedImage decoded = PhotoOrientation.apply(decode(bytes, false), PhotoOrientation.read(bytes));
            return new Photo(png(decoded), digest(bytes, hint));
        } catch (IOException | IllegalArgumentException e) {
            throw invalidPhoto();
        }
    }

    public Candidate candidate(byte[] bytes) {
        try {
            BufferedImage image = decode(bytes, true);
            List<String> failures = new ArrayList<>();
            if (image.getWidth() != OUTPUT_SIZE || image.getHeight() != OUTPUT_SIZE) {
                failures.add("CANVAS_MUST_BE_1024_SQUARE");
            }
            if (!image.getColorModel().hasAlpha()) failures.add("TRANSPARENT_BACKGROUND_REQUIRED");
            long visible = 0;
            long transparent = 0;
            boolean touchesBorder = false;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int alpha = image.getRGB(x, y) >>> 24;
                    if (alpha <= 16) transparent++;
                    if (alpha > 32) {
                        visible++;
                        if (x < 8 || y < 8 || x >= image.getWidth() - 8 || y >= image.getHeight() - 8) {
                            touchesBorder = true;
                        }
                    }
                }
            }
            long pixels = (long) image.getWidth() * image.getHeight();
            if (visible < pixels / 100) failures.add("EMPTY_OR_TOO_SMALL_OBJECT");
            if (transparent < pixels / 10) failures.add("BACKGROUND_NOT_REMOVED");
            if (touchesBorder) failures.add("OBJECT_TOUCHES_CANVAS_BORDER");
            return new Candidate(png(image), List.copyOf(failures));
        } catch (IOException | IllegalArgumentException e) {
            throw new FurnitureAiFailure("INVALID_IMAGE_OUTPUT");
        }
    }

    // 비전 모델이 alpha=0인 RGB를 배경으로 오인하지 않게 실제 합성 결과를 검수 입력으로 사용함.
    // 저장할 원본 PNG/알파는 변경하지 않음.
    public byte[] preview(byte[] bytes, int backgroundRgb) {
        try {
            BufferedImage image = decode(bytes, false);
            var preview = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = preview.createGraphics();
            try {
                graphics.setColor(new java.awt.Color(backgroundRgb));
                graphics.fillRect(0, 0, preview.getWidth(), preview.getHeight());
                graphics.drawImage(image, 0, 0, null);
            } finally { graphics.dispose(); }
            return png(preview);
        } catch (IOException e) { throw new FurnitureAiFailure("INVALID_IMAGE_OUTPUT"); }
    }

    private BufferedImage decode(byte[] bytes, boolean output) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException();
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException();
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(format.equals("png") || (!output && (format.equals("jpeg") || format.equals("jpg"))))) {
                    throw new IOException();
                }
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long pixelLimit = output ? 4_194_304L : 32_000_000L;
                if (width < 32 || height < 32 || width > 8192 || height > 8192
                        || (long) width * height > pixelLimit) throw new IOException();
                var params = reader.getDefaultReadParam();
                if (!output) {
                    int sample = Math.max(1, (Math.max(width, height) + 1535) / 1536);
                    params.setSourceSubsampling(sample, sample, 0, 0);
                }
                return reader.read(0, params);
            } finally {
                reader.dispose();
            }
        }
    }

    private byte[] png(BufferedImage image) throws IOException {
        var out = new ByteArrayOutputStream();
        // 재인코딩으로 EXIF·GPS·원본 텍스트 metadata를 저장/전송 대상에서 제거함.
        if (!ImageIO.write(image, "png", out)) throw new IOException();
        if (out.size() > MAX_BYTES) throw new IOException();
        return out.toByteArray();
    }

    private String digest(byte[] bytes, String hint) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(bytes);
            hash.update((byte) 0);
            return HexFormat.of().formatHex(hash.digest(hint.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private BusinessException invalidPhoto() {
        return new BusinessException(FurnitureGenerationErrorCode.FURNITURE_PHOTO_INVALID);
    }
}
