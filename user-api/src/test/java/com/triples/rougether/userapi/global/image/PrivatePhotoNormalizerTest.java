package com.triples.rougether.userapi.global.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.triples.rougether.userapi.global.image.PrivatePhotoNormalizer.InvalidPhotoException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class PrivatePhotoNormalizerTest {

    private static MockMultipartFile png(int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        // 1비트 이미지라 큰 크기도 메모리를 적게 씀.
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png", out);
        return new MockMultipartFile("file", out.toByteArray());
    }

    private static void rejected(MockMultipartFile file) {
        assertThatThrownBy(() -> PrivatePhotoNormalizer.normalize(file)).isInstanceOf(InvalidPhotoException.class);
    }

    @Test
    void 파일이_없거나_10MiB를_넘으면_거부한다() {
        rejected(null);
        rejected(new MockMultipartFile("file", new byte[0]));
        rejected(new MockMultipartFile("file", new byte[10 * 1024 * 1024 + 1]));
    }

    @Test
    void 가로세로_8192px와_3200만_화소를_넘으면_거부한다() throws Exception {
        rejected(png(8193, 1));
        rejected(png(1, 8193));
        rejected(png(8192, 3907));
    }

    @Test
    void 화소_한도_안의_큰_사진은_긴_변_1600px로_줄인다() throws Exception {
        var photo = PrivatePhotoNormalizer.normalize(png(8192, 3906));
        assertThat(Math.max(photo.width(), photo.height())).isEqualTo(1600);
    }

    @Test
    void 긴_변이_1600px_이하면_크기를_유지하고_넘으면_줄인다() throws Exception {
        var exact = PrivatePhotoNormalizer.normalize(png(1600, 900));
        assertThat(exact.width()).isEqualTo(1600);
        assertThat(exact.height()).isEqualTo(900);

        var over = PrivatePhotoNormalizer.normalize(png(1601, 900));
        assertThat(over.width()).isEqualTo(1600);
        assertThat(over.height()).isEqualTo(899);
    }

    @Test
    void 디코딩_슬롯이_가득차면_바쁨으로_거부하고_실패해도_슬롯을_돌려준다() throws Exception {
        int permits = PrivatePhotoNormalizer.DECODING.availablePermits();
        assertThat(permits).isEqualTo(1);
        PrivatePhotoNormalizer.DECODING.acquire(permits);
        try {
            var file = png(16, 16);
            assertThatThrownBy(() -> PrivatePhotoNormalizer.normalize(file))
                    .isInstanceOf(PrivatePhotoNormalizer.PhotoBusyException.class);
        } finally {
            PrivatePhotoNormalizer.DECODING.release(permits);
        }

        rejected(new MockMultipartFile("file", "not a photo".getBytes()));
        assertThat(PrivatePhotoNormalizer.DECODING.availablePermits()).isEqualTo(permits);
    }

    @Test
    void 스캔을_수백번_반복한_progressive_JPEG도_앞쪽_스캔만_읽어_빠르게_끝난다() {
        var file = new MockMultipartFile("file", ManyScanJpeg.of(8192, 3906, 801));

        var photo = assertTimeout(Duration.ofSeconds(15), () -> PrivatePhotoNormalizer.normalize(file));

        assertThat(photo.width()).isEqualTo(1600);
    }

    // 파일은 작지만 스캔마다 이미지 전체를 다시 출력하게 만드는 progressive JPEG.
    private static final class ManyScanJpeg {
        private static final int COMPONENTS = 3;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int accumulator;
        private int bitCount;

        static byte[] of(int width, int height, int scans) {
            var jpeg = new ManyScanJpeg();
            long blocks = (long) ((width + 7) / 8) * ((height + 7) / 8);
            jpeg.header(width, height);
            jpeg.dcScan(blocks);
            for (int scan = 0; scan < scans; scan++) jpeg.acScan(blocks, scan % COMPONENTS + 1);
            jpeg.bytes(0xFF, 0xD9);
            return jpeg.out.toByteArray();
        }

        private void header(int width, int height) {
            bytes(0xFF, 0xD8, 0xFF, 0xDB);
            word(67);
            bytes(0);
            for (int i = 0; i < 64; i++) bytes(1);
            bytes(0xFF, 0xC2);
            word(8 + 3 * COMPONENTS);
            bytes(8);
            word(height);
            word(width);
            bytes(COMPONENTS);
            for (int component = 1; component <= COMPONENTS; component++) bytes(component, 0x11, 0);
            huffmanTable(0x00, 0x00);
            huffmanTable(0x10, 0xE0);
        }

        private void huffmanTable(int id, int symbol) {
            bytes(0xFF, 0xC4);
            word(20);
            bytes(id, 1);
            for (int i = 1; i < 16; i++) bytes(0);
            bytes(symbol);
        }

        private void dcScan(long blocks) {
            bytes(0xFF, 0xDA);
            word(6 + 2 * COMPONENTS);
            bytes(COMPONENTS);
            for (int component = 1; component <= COMPONENTS; component++) bytes(component, 0x00);
            bytes(0, 0, 0);
            for (long i = 0; i < blocks * COMPONENTS; i++) bits(0, 1);
            flush();
        }

        private void acScan(long blocks, int component) {
            bytes(0xFF, 0xDA);
            word(8);
            bytes(1, component, 0x00, 1, 63, 0);
            for (long remaining = blocks; remaining > 0; remaining -= 32767) {
                bits(0, 1);
                bits(16383, 14);
            }
            flush();
        }

        private void bytes(int... values) {
            for (int value : values) out.write(value);
        }

        private void word(int value) {
            bytes((value >> 8) & 255, value & 255);
        }

        private void bits(int value, int length) {
            for (int i = length - 1; i >= 0; i--) {
                accumulator = (accumulator << 1) | ((value >> i) & 1);
                if (++bitCount == 8) writeByte();
            }
        }

        private void writeByte() {
            out.write(accumulator);
            if (accumulator == 0xFF) out.write(0);
            accumulator = 0;
            bitCount = 0;
        }

        private void flush() {
            while (bitCount != 0) bits(1, 1);
        }
    }

    private static MockMultipartFile encoded(BufferedImage image, String format, byte[] exif) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        byte[] bytes = out.toByteArray();
        if (exif == null) return new MockMultipartFile("file", bytes);
        var withExif = new ByteArrayOutputStream();
        withExif.write(bytes, 0, 2);
        withExif.write(exif);
        withExif.write(bytes, 2, bytes.length - 2);
        return new MockMultipartFile("file", withExif.toByteArray());
    }

    // 왼쪽 절반은 빨강, 오른쪽 절반은 파랑.
    private static BufferedImage leftRedRightBlue(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.RED); graphics.fillRect(0, 0, width / 2, height);
        graphics.setColor(java.awt.Color.BLUE); graphics.fillRect(width / 2, 0, width - width / 2, height);
        graphics.dispose();
        return image;
    }

    private static java.awt.Color center(PrivatePhotoNormalizer.Photo photo) throws Exception {
        var image = ImageIO.read(new java.io.ByteArrayInputStream(photo.bytes()));
        return new java.awt.Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
    }

    @Test
    void 자르기는_비율로_지정한_영역만_남긴다() throws Exception {
        var file = encoded(leftRedRightBlue(200, 100), "png", null);

        var right = PrivatePhotoNormalizer.normalize(file, new PrivatePhotoNormalizer.Crop(0.5, 0, 0.5, 1));
        assertThat(right.width()).isEqualTo(100);
        assertThat(right.height()).isEqualTo(100);
        assertThat(center(right).getBlue()).isGreaterThan(200);
        assertThat(center(right).getRed()).isLessThan(50);

        var left = PrivatePhotoNormalizer.normalize(file, new PrivatePhotoNormalizer.Crop(0.1, 0.2, 0.3, 0.6));
        assertThat(left.width()).isEqualTo(60);
        assertThat(left.height()).isEqualTo(60);
        assertThat(center(left).getRed()).isGreaterThan(200);
    }

    @Test
    void 자르기_좌표는_회전_보정_후_이미지를_기준으로_한다() throws Exception {
        // orientation 6: 저장된 200x100을 시계 방향으로 돌려 100x200으로 보여 줌. 원본의 왼쪽(빨강)이 위, 오른쪽(파랑)이 아래가 됨.
        byte[] exif = java.util.HexFormat.of().parseHex(
                "ffe1002245786966000049492a0008000000010012010300010000000600000000000000");
        var file = encoded(leftRedRightBlue(200, 100), "jpeg", exif);
        var whole = PrivatePhotoNormalizer.normalize(file);
        assertThat(whole.width()).isEqualTo(100);
        assertThat(whole.height()).isEqualTo(200);

        var bottom = PrivatePhotoNormalizer.normalize(file, new PrivatePhotoNormalizer.Crop(0, 0.5, 1, 0.5));
        assertThat(bottom.width()).isEqualTo(100);
        assertThat(bottom.height()).isEqualTo(100);
        assertThat(center(bottom).getBlue()).isGreaterThan(180);
        assertThat(center(bottom).getRed()).isLessThan(80);

        var top = PrivatePhotoNormalizer.normalize(file, new PrivatePhotoNormalizer.Crop(0.2, 0.1, 0.6, 0.3));
        assertThat(top.width()).isEqualTo(60);
        assertThat(top.height()).isEqualTo(60);
        assertThat(center(top).getRed()).isGreaterThan(180);
    }

    @Test
    void 큰_사진을_자르면_잘린_영역의_해상도를_살려_긴_변_1600px로_맞춘다() throws Exception {
        // 전체를 먼저 1600px로 줄인 뒤 자르면 1000x750이 됨. 잘린 영역(2000x1500)을 기준으로 줄여야 함.
        var photo = PrivatePhotoNormalizer.normalize(png(4000, 3000), new PrivatePhotoNormalizer.Crop(0.25, 0.25, 0.5, 0.5));
        assertThat(photo.width()).isEqualTo(1600);
        assertThat(photo.height()).isEqualTo(1200);

        var small = PrivatePhotoNormalizer.normalize(png(4000, 3000), new PrivatePhotoNormalizer.Crop(0, 0, 0.1, 0.1));
        assertThat(small.width()).isEqualTo(400);
        assertThat(small.height()).isEqualTo(300);
    }

    @Test
    void 자르기_범위가_잘못됐거나_결과가_16px보다_작으면_거부한다() throws Exception {
        for (double[] c : new double[][] {{-0.01, 0, 0.5, 0.5}, {0, 1, 0.5, 0.5}, {0, 0, 0, 0.5}, {0, 0, 0.5, 1.01},
                {0.6, 0, 0.5, 0.5}, {0, 0.6, 0.5, 0.5}, {Double.NaN, 0, 0.5, 0.5}, {0, 0, Double.POSITIVE_INFINITY, 0.5}}) {
            assertThatThrownBy(() -> new PrivatePhotoNormalizer.Crop(c[0], c[1], c[2], c[3]))
                    .isInstanceOf(InvalidPhotoException.class);
        }
        var file = png(400, 200);
        assertThatThrownBy(() -> PrivatePhotoNormalizer.normalize(file, new PrivatePhotoNormalizer.Crop(0, 0, 0.03, 1)))
                .isInstanceOf(InvalidPhotoException.class);
        // 아주 길쭉한 영역은 긴 변을 줄인 뒤 짧은 변이 16px보다 작아짐(8000x40 → 1600x8).
        var wide = png(8000, 400);
        assertThatThrownBy(() -> PrivatePhotoNormalizer.normalize(wide, new PrivatePhotoNormalizer.Crop(0, 0, 1, 0.1)))
                .isInstanceOf(InvalidPhotoException.class);
        // 실패해도 디코딩 슬롯을 돌려줌.
        assertThat(PrivatePhotoNormalizer.DECODING.availablePermits()).isEqualTo(1);
        var edge = PrivatePhotoNormalizer.normalize(file, new PrivatePhotoNormalizer.Crop(0.96, 0.92, 0.04, 0.08));
        assertThat(edge.width()).isEqualTo(16);
        assertThat(edge.height()).isEqualTo(16);
    }
}
