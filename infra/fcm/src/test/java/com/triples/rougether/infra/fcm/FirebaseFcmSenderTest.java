package com.triples.rougether.infra.fcm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

// FirebaseFcmSender.send()는 FirebaseMessaging.getInstance()라는 static 의존 때문에 Mockito로 직접 단위테스트하기
// 어려워, 토큰이 500개를 넘을 때 IllegalArgumentException(addAllTokens)이 나던 버그를 고친 partition 분할 로직만 검증한다.
class FirebaseFcmSenderTest {

    private List<String> tokens(int count) {
        return IntStream.range(0, count).mapToObj(i -> "token-" + i).toList();
    }

    @Test
    void 정확히_500개면_한_묶음이다() {
        List<List<String>> chunks = FirebaseFcmSender.partition(tokens(500), 500);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(500);
    }

    @Test
    void 토큰이_501개면_500개와_1개로_나뉜다() {
        List<List<String>> chunks = FirebaseFcmSender.partition(tokens(501), 500);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(500);
        assertThat(chunks.get(1)).hasSize(1);
    }

    @Test
    void 토큰이_500개_미만이면_한_묶음이다() {
        List<List<String>> chunks = FirebaseFcmSender.partition(tokens(3), 500);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).hasSize(3);
    }
}
