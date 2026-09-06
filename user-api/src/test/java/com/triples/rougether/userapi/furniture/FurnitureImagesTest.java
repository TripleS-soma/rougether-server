package com.triples.rougether.userapi.furniture;

import static org.assertj.core.api.Assertions.*;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.furniture.service.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FurnitureImagesTest {
    private final FurnitureImages images = new FurnitureImages();

    @Test void 실제_디코딩과_치수로_입력_검증() {
        var forged = new MockMultipartFile("photo", "safe.png", "image/png", "not an image".getBytes());
        assertThatThrownBy(() -> images.photo(forged, "")).isInstanceOf(BusinessException.class);
        var oversized = new MockMultipartFile("photo", new byte[FurnitureImages.MAX_BYTES + 1]);
        assertThatThrownBy(() -> images.photo(oversized, "")).isInstanceOf(BusinessException.class);
    }

    @Test void 큰_카메라_사진은_축소하고_프롬프트도_멱등_해시에_포함() throws Exception {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4096, 3072, BufferedImage.TYPE_INT_RGB), "jpg", buffer);
        var file = new MockMultipartFile("photo", "chair.jpg", "image/jpeg", buffer.toByteArray());
        var one = images.photo(file, "앞 의자");
        var two = images.photo(file, "앞 의자");
        var other = images.photo(file, "뒤 의자");
        var decoded = ImageIO.read(new ByteArrayInputStream(one.png()));
        assertThat(decoded.getWidth()).isLessThanOrEqualTo(1536);
        assertThat(one.digest()).isEqualTo(two.digest()).isNotEqualTo(other.digest());
    }

    @Test void 불투명_배경과_테두리_잘림은_통과하지_못함() {
        assertThat(images.candidate(FurnitureFixtures.png(false, false)).failures())
                .contains("TRANSPARENT_BACKGROUND_REQUIRED", "BACKGROUND_NOT_REMOVED");
        assertThat(images.candidate(FurnitureFixtures.png(true, true)).failures())
                .contains("OBJECT_TOUCHES_CANVAS_BORDER");
        assertThat(images.candidate(FurnitureFixtures.png(true, false)).passed()).isTrue();
    }

    @Test void 카메라_JPEG의_EXIF_회전을_적용한_뒤_metadata를_제거() throws Exception {
        var original = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 40, BufferedImage.TYPE_INT_RGB), "jpg", original);
        byte[] exif = java.util.HexFormat.of().parseHex(
                "ffe1002245786966000049492a0008000000010012010300010000000600000000000000");
        byte[] jpeg = original.toByteArray();
        var withExif = new ByteArrayOutputStream();
        withExif.write(jpeg, 0, 2);
        withExif.write(exif);
        withExif.write(jpeg, 2, jpeg.length - 2);
        var normalized = images.photo(new MockMultipartFile("photo", withExif.toByteArray()), "");
        var decoded = ImageIO.read(new ByteArrayInputStream(normalized.png()));
        assertThat(decoded.getWidth()).isEqualTo(40);
        assertThat(decoded.getHeight()).isEqualTo(64);
        assertThat(new String(normalized.png(), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Exif");
    }

    @Test void 빈_이미지와_잘못된_생성물은_지급_대상이_아님() throws Exception {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB), "png", buffer);
        assertThat(images.candidate(buffer.toByteArray()).failures()).contains("EMPTY_OR_TOO_SMALL_OBJECT");
        assertThatThrownBy(() -> images.candidate(new byte[]{1, 2})).isInstanceOf(FurnitureAiFailure.class);
    }

    @Test void 투명_픽셀의_숨겨진_RGB는_검수_미리보기에_나타나지_않음() throws Exception {
        var source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x00FF0000);
        source.setRGB(32, 32, 0xFF0000FF);
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(source, "png", buffer);
        byte[] original = buffer.toByteArray();
        var light = ImageIO.read(new ByteArrayInputStream(images.preview(original, 0xFAF7F1)));
        var dark = ImageIO.read(new ByteArrayInputStream(images.preview(original, 0x30343B)));
        assertThat(light.getRGB(0, 0)).isEqualTo(0xFFFAF7F1);
        assertThat(dark.getRGB(0, 0)).isEqualTo(0xFF30343B);
        assertThat(light.getRGB(32, 32)).isEqualTo(0xFF0000FF);
        assertThat(ImageIO.read(new ByteArrayInputStream(original)).getRGB(0, 0)).isEqualTo(0x00FF0000);
    }
}
