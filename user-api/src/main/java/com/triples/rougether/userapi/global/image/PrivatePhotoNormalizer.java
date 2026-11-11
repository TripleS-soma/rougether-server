package com.triples.rougether.userapi.global.image;

import com.triples.rougether.common.image.PhotoOrientation;
import java.awt.Color;
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

    public record Photo(byte[] bytes, int width, int height) { }

    public static final class InvalidPhotoException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    // 디코딩 슬롯을 얻지 못함. 호출한 쪽은 잠시 후 재시도하도록 503으로 응답함.
    public static final class PhotoBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private PrivatePhotoNormalizer() { }

    public static Photo normalize(MultipartFile file) {
        acquireDecodeSlot();
        try {
            return decode(file);
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

    private static Photo decode(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw new InvalidPhotoException();
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new InvalidPhotoException();
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
                    params.setSourceSubsampling(Math.max(1, Math.max(width, height) / MAX_EDGE),
                            Math.max(1, Math.max(width, height) / MAX_EDGE), 0, 0);
                    decoded = reader.read(0, params);
                } finally { reader.dispose(); }
            }
            decoded = PhotoOrientation.apply(decoded, PhotoOrientation.read(bytes));
            double ratio = Math.min(1.0, (double) MAX_EDGE / Math.max(decoded.getWidth(), decoded.getHeight()));
            int width = Math.max(1, (int) Math.round(decoded.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(decoded.getHeight() * ratio));
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
