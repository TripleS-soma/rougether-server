package com.triples.rougether.userapi.global.image;

import com.triples.rougether.common.image.PhotoOrientation;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

// 비공개 사용자 사진(피드·채팅)의 공통 규격. 도메인별 오류 코드는 호출한 쪽에서 바꿔 던짐.
public final class PrivatePhotoNormalizer {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_EDGE = 1600;
    private static final int MAX_CONCURRENT_DECODES = 1;
    private static final long DECODE_WAIT_SECONDS = 5;
    // 스캔을 수백 번 반복한 작은 progressive JPEG가 디코딩 슬롯을 오래 잡지 못하게 함. 일반 사진은 10개 안팎임.
    private static final int MAX_PROGRESSIVE_PASSES = 16;
    // 규격 안의 progressive JPEG 한 장은 힙 약 100MB에 더해 힙 밖(libjpeg)에서 약 215MB를 씀.
    // 컨테이너 한도(1280MiB) 안에 들도록 JVM 전체에서 한 번에 한 장만 디코딩하고, 대기는 도착 순서대로 처리함.
    static final Semaphore DECODING = new Semaphore(MAX_CONCURRENT_DECODES, true);

    // 자른 결과가 이보다 작으면 거부함(가로·세로 각각).
    private static final int MIN_CROP_EDGE = 16;
    private static final double CROP_TOLERANCE = 1e-6;

    public record Photo(byte[] bytes, int width, int height) { }

    // 잘라낼 영역. 회전 보정 후 이미지의 가로·세로에 대한 0~1 비율임.
    public record Crop(double x, double y, double width, double height) {
        public Crop {
            if (!(x >= 0 && x < 1 && y >= 0 && y < 1 && width > 0 && width <= 1 && height > 0 && height <= 1
                    && x + width <= 1 + CROP_TOLERANCE && y + height <= 1 + CROP_TOLERANCE)) {
                throw new InvalidPhotoException();
            }
        }

        // 원본(회전 보정 전) 픽셀 영역으로 바꿈. 디코딩 단계에서 이 영역만 읽어 잘린 부분의 해상도를 살림.
        Rectangle sourceRegion(int sourceWidth, int sourceHeight, int orientation) {
            boolean swap = orientation >= 5 && orientation <= 8;
            int orientedWidth = swap ? sourceHeight : sourceWidth;
            int orientedHeight = swap ? sourceWidth : sourceHeight;
            int left = (int) Math.round(x * orientedWidth);
            int top = (int) Math.round(y * orientedHeight);
            int right = Math.min(orientedWidth, (int) Math.round((x + width) * orientedWidth));
            int bottom = Math.min(orientedHeight, (int) Math.round((y + height) * orientedHeight));
            int w = right - left;
            int h = bottom - top;
            if (w < MIN_CROP_EDGE || h < MIN_CROP_EDGE) throw new InvalidPhotoException();
            // PhotoOrientation.apply의 좌표 변환을 거꾸로 적용함.
            return switch (orientation) {
                case 2 -> new Rectangle(sourceWidth - right, top, w, h);
                case 3 -> new Rectangle(sourceWidth - right, sourceHeight - bottom, w, h);
                case 4 -> new Rectangle(left, sourceHeight - bottom, w, h);
                case 5 -> new Rectangle(top, left, h, w);
                case 6 -> new Rectangle(top, sourceHeight - right, h, w);
                case 7 -> new Rectangle(sourceWidth - bottom, sourceHeight - right, h, w);
                case 8 -> new Rectangle(sourceWidth - bottom, left, h, w);
                default -> new Rectangle(left, top, w, h);
            };
        }
    }

    public static final class InvalidPhotoException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    // 디코딩 슬롯을 얻지 못함. 호출한 쪽은 잠시 후 재시도하도록 503으로 응답함.
    public static final class PhotoBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private PrivatePhotoNormalizer() { }

    public static Photo normalize(MultipartFile file) {
        return normalize(file, null);
    }

    // 디코딩 → 회전 보정 → 자르기 → 긴 변 축소 → JPEG 순서로 처리함. crop이 null이면 자르지 않음.
    public static Photo normalize(MultipartFile file, Crop crop) {
        acquireDecodeSlot();
        try {
            return decode(file, crop);
        } finally {
            DECODING.release();
        }
    }

    private static void acquireDecodeSlot() {
        try {
            if (!DECODING.tryAcquire(DECODE_WAIT_SECONDS, TimeUnit.SECONDS)) throw new PhotoBusyException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhotoBusyException();
        }
    }

    private static Photo decode(MultipartFile file, Crop crop) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw new InvalidPhotoException();
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new InvalidPhotoException();
            int orientation = PhotoOrientation.read(bytes);
            BufferedImage decoded;
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new InvalidPhotoException();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!format.equals("jpeg") && !format.equals("png")) throw new InvalidPhotoException();
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 32_000_000)
                        throw new InvalidPhotoException();
                    var params = reader.getDefaultReadParam();
                    params.setSourceProgressivePasses(0, MAX_PROGRESSIVE_PASSES);
                    // 자를 때는 잘린 영역의 긴 변을 기준으로 축소 비율을 정함. 사진에 따라 읽는 픽셀이 늘 수 있지만 최악 상한(긴 변 3,200px 미만)은 자르지 않을 때와 같음.
                    Rectangle region = crop == null ? null : crop.sourceRegion(width, height, orientation);
                    int longEdge = region == null ? Math.max(width, height) : Math.max(region.width, region.height);
                    int sampling = Math.max(1, longEdge / MAX_EDGE);
                    params.setSourceSubsampling(sampling, sampling, 0, 0);
                    if (region != null) params.setSourceRegion(region);
                    decoded = reader.read(0, params);
                } finally { reader.dispose(); }
            }
            decoded = PhotoOrientation.apply(decoded, orientation);
            double ratio = Math.min(1.0, (double) MAX_EDGE / Math.max(decoded.getWidth(), decoded.getHeight()));
            int width = Math.max(1, (int) Math.round(decoded.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(decoded.getHeight() * ratio));
            // 아주 길쭉하게 자르면 축소 뒤 짧은 변이 기준보다 작아질 수 있음.
            if (crop != null && (width < MIN_CROP_EDGE || height < MIN_CROP_EDGE)) throw new InvalidPhotoException();
            var normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = normalized.createGraphics();
            try {
                graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(decoded, 0, 0, width, height, null);
            } finally { graphics.dispose(); }
            var output = new ByteArrayOutputStream();
            // 새 JPEG로 인코딩하여 위치·EXIF·텍스트 metadata와 원본 바이트를 공개하지 않음.
            if (!ImageIO.write(normalized, "jpeg", output)) throw new InvalidPhotoException();
            return new Photo(output.toByteArray(), width, height);
        } catch (IOException | IllegalArgumentException e) { throw new InvalidPhotoException(); }
    }
}
