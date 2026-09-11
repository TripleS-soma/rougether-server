package com.triples.rougether.preprocessing;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class VipsPhotoPreprocessorTest {
    @Test void 지원하지_않는_형식과_용량은_네이티브_초기화_전에_거절함() {
        var codec = new VipsPhotoPreprocessor();
        assertThatThrownBy(() -> codec.prepare(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.prepare("GIF89a".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.prepare(new byte[VipsPhotoPreprocessor.MAX_BYTES + 1])).isInstanceOf(IllegalArgumentException.class);
    }
    @Test @EnabledIfEnvironmentVariable(named = "VIPS_NATIVE_PATH", matches = ".+")
    void 실제_libvips로_크기와_알파를_보존하고_반복_처리함() throws Exception {
        var codec = new VipsPhotoPreprocessor();
        var input = new BufferedImage(128, 96, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 96; y++) for (int x = 0; x < 128; x++) input.setRGB(x, y, (x * 2 << 24) | 0x204060);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(input, "png", bytes);
        for (int i = 0; i < 20; i++) {
            try (var photo = codec.prepare(bytes.toByteArray()); var stream = photo.openStream()) {
                var output = ImageIO.read(stream);
                assertThat(output.getWidth()).isEqualTo(128);
                assertThat(output.getHeight()).isEqualTo(96);
                for (int x = 0; x < 128; x++) assertThat(output.getRGB(x, 20) >>> 24).isEqualTo(x * 2);
            }
        }
    }
    @Test @EnabledIfEnvironmentVariable(named = "VIPS_NATIVE_PATH", matches = ".+")
    void 큰_JPEG는_1536이하로_축소하고_잘린_입력은_거절함() throws Exception {
        var image = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", out);
        var codec = new VipsPhotoPreprocessor();
        try (var photo = codec.prepare(out.toByteArray())) {
            assertThat(photo.width()).isEqualTo(1000);
            assertThat(photo.height()).isEqualTo(500);
        }
        assertThatThrownBy(() -> codec.prepare(Arrays.copyOf(out.toByteArray(), 200))).isInstanceOf(IllegalArgumentException.class);
    }
}
