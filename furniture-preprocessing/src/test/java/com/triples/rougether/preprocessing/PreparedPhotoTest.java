package com.triples.rougether.preprocessing;

import static org.assertj.core.api.Assertions.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class PreparedPhotoTest {
    @Test void 처리_Arena가_닫혀도_PNG는_재시도마다_처음부터_읽힘() throws Exception {
        PreparedPhoto photo;
        byte[] bytes = {1, 2, 3, 4};
        try (var input = Arena.ofConfined()) {
            var source = input.allocate(bytes.length);
            source.copyFrom(MemorySegment.ofArray(bytes));
            photo = PreparedPhoto.copyOf(source, 32, 32);
        }
        try (photo; var first = photo.openStream(); var retry = photo.openStream()) {
            assertThat(first.read()).isEqualTo(1);
            assertThat(retry.readAllBytes()).isEqualTo(bytes);
            first.mark(100);
            assertThat(first.readAllBytes()).isEqualTo(new byte[]{2, 3, 4});
            first.reset();
            assertThat(first.read()).isEqualTo(2);
        }
        assertThat(photo.isAlive()).isFalse();
        assertThatThrownBy(photo::openStream).isInstanceOf(IllegalStateException.class);
    }
    @Test void 소유권_반납_후_남은_스트림도_해제된_메모리에_접근하지_못함() throws Exception {
        var photo = PreparedPhoto.copyOf(MemorySegment.ofArray(new byte[]{1, 2}), 32, 32);
        var stream = photo.openStream();
        photo.close(); photo.close();
        assertThatThrownBy(stream::read).isInstanceOf(IllegalStateException.class);
    }
    @Test void 스트림을_닫아도_다음_재시도에_필요한_PNG는_살아있음() throws Exception {
        try (var photo = PreparedPhoto.copyOf(MemorySegment.ofArray(new byte[]{7}), 32, 32)) {
            var first = photo.openStream(); first.close();
            assertThatThrownBy(first::read).isInstanceOf(java.io.IOException.class);
            assertThat(photo.openStream().read()).isEqualTo(7);
            assertThat(photo.isAlive()).isTrue();
        }
    }
}
