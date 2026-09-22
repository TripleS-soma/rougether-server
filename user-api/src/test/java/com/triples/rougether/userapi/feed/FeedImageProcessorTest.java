package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.feed.service.FeedImageProcessor;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FeedImageProcessorTest {
    private final FeedImageProcessor processor = new FeedImageProcessor();

    @Test void 실제_이미지형식과_용량_치수를_검증한다() throws Exception {
        invalid(new byte[0]);
        invalid("not a photo".getBytes(StandardCharsets.UTF_8));
        invalid(new byte[10 * 1024 * 1024 + 1]);
        invalid(encoded(32, 32, "gif"));
        invalid(encoded(8193, 1, "png"));
    }

    @Test void 큰사진은_축소하고_투명PNG는_흰배경_JPEG로_변환한다() throws Exception {
        var photo = processor.process(new MockMultipartFile("file", encoded(4096, 3072, "png")));
        var decoded = ImageIO.read(new ByteArrayInputStream(photo.bytes()));
        assertThat(photo.width()).isEqualTo(1600);
        assertThat(photo.height()).isEqualTo(1200);
        assertThat(decoded.getWidth()).isEqualTo(photo.width());
        assertThat(decoded.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0xFFFFFF);
        assertThat(photo.bytes()[0] & 255).isEqualTo(255);
        assertThat(photo.bytes()[1] & 255).isEqualTo(216);
    }

    @Test void EXIF_회전은_적용하고_원본_metadata는_제거한다() throws Exception {
        byte[] jpeg = encoded(64, 40, "jpeg");
        byte[] exif = HexFormat.of().parseHex(
                "ffe1002245786966000049492a0008000000010012010300010000000600000000000000");
        var original = new ByteArrayOutputStream();
        original.write(jpeg, 0, 2);
        original.write(exif);
        original.write(jpeg, 2, jpeg.length - 2);
        var photo = processor.process(new MockMultipartFile("file", original.toByteArray()));
        assertThat(photo.width()).isEqualTo(40);
        assertThat(photo.height()).isEqualTo(64);
        assertThat(new String(photo.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    private byte[] encoded(int width, int height, String format) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, format.equals("png")
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
    private void invalid(byte[] bytes) {
        assertThatThrownBy(() -> processor.process(new MockMultipartFile("file", "forged.jpg", "image/jpeg", bytes)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode().code()).isEqualTo("FEED_IMAGE_INVALID"));
    }
}
