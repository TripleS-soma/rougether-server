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
}
