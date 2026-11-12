package com.triples.rougether.userapi.furniture.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.furniture.service.FurnitureImages;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class FurnitureRawPhotoTest {
    @Test void 기존_요청해시를_유지하며_MIME이_틀려도_실제_헤더로_구분함() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "png", output);
        var file = new MockMultipartFile("photo", "photo.jpg", "application/octet-stream", output.toByteArray());
        var raw = FurnitureRawPhoto.inspect(file, "한글 힌트");
        assertThat(raw.contentType()).isEqualTo("image/png");
        assertThat(raw.requestDigest()).isEqualTo(new FurnitureImages().photo(file, "한글 힌트").digest());
        assertThat(FurnitureRawPhoto.inspect(file, "다른 힌트").requestDigest()).isNotEqualTo(raw.requestDigest());
    }

    @Test void 큰_파일도_8KiB_버퍼로_읽고_getBytes를_사용하지_않음() throws Exception {
        byte[] jpeg = {(byte) 255, (byte) 216, (byte) 255};
        var file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn((long) FurnitureRawPhoto.MAX_BYTES);
        var stream = new InputStream() {
            int position;
            @Override public int read() { return position < FurnitureRawPhoto.MAX_BYTES ? value(position++) : -1; }
            @Override public int read(byte[] target, int offset, int length) {
                assertThat(length).isLessThanOrEqualTo(8192);
                if (position == FurnitureRawPhoto.MAX_BYTES) return -1;
                int count = Math.min(length, FurnitureRawPhoto.MAX_BYTES - position);
                Arrays.fill(target, offset, offset + count, (byte) 0);
                for (int i = 0; i < count && position + i < jpeg.length; i++) target[offset + i] = jpeg[position + i];
                position += count;
                return count;
            }
            int value(int index) { return index < jpeg.length ? jpeg[index] & 255 : 0; }
        };
        when(file.getInputStream()).thenReturn(stream);
        assertThat(FurnitureRawPhoto.inspect(file, "").bytes()).isEqualTo(FurnitureRawPhoto.MAX_BYTES);
        verify(file, never()).getBytes();
    }

    @Test void 크기를_속인_스트림은_선언크기_다음_한바이트까지만_읽음() throws Exception {
        var input = spy(new ByteArrayInputStream(new byte[100_000]));
        var file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(20L);
        when(file.getInputStream()).thenReturn(input);
        assertThatThrownBy(() -> FurnitureRawPhoto.inspect(file, "")).isInstanceOf(BusinessException.class);
        assertThat(input.available()).isEqualTo(100_000 - 21);
        verify(input).close();
    }

    @Test void 빈파일_한도초과_가짜형식은_거절함() throws Exception {
        assertThatThrownBy(() -> FurnitureRawPhoto.inspect(new MockMultipartFile("photo", new byte[0]), ""))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> FurnitureRawPhoto.inspect(new MockMultipartFile("photo", new byte[]{1, 2, 3}), ""))
                .isInstanceOf(BusinessException.class);
        var file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn((long) FurnitureRawPhoto.MAX_BYTES + 1);
        assertThatThrownBy(() -> FurnitureRawPhoto.inspect(file, "")).isInstanceOf(BusinessException.class);
        verify(file, never()).getInputStream();
    }
}
