package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.common.image.PhotoOrientation;
import static com.triples.rougether.userapi.feed.error.FeedErrorCode.FEED_IMAGE_INVALID;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FeedImageProcessor {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_EDGE = 1600;
    public record Photo(byte[] bytes, int width, int height) { }

    public Photo process(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) throw invalid();
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw invalid();
            BufferedImage decoded;
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!format.equals("jpeg") && !format.equals("png")) throw invalid();
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 32_000_000)
                        throw invalid();
                    var params = reader.getDefaultReadParam();
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
            if (!ImageIO.write(normalized, "jpeg", output)) throw invalid();
            return new Photo(output.toByteArray(), width, height);
        } catch (IOException | IllegalArgumentException e) { throw invalid(); }
    }
    private BusinessException invalid() { return new BusinessException(FEED_IMAGE_INVALID); }
}
