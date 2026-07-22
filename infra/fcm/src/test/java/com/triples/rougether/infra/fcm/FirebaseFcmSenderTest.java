package com.triples.rougether.infra.fcm;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.MulticastMessage;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

// FirebaseFcmSender.send()는 FirebaseMessaging.getInstance()라는 static 의존 때문에 Mockito로 직접 단위테스트하기
// 어려워, 토큰이 500개를 넘을 때 IllegalArgumentException(addAllTokens)이 나던 버그를 고친 partition 분할 로직과
// 발송 메시지 구성(buildMessage)만 검증한다.
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

    // SDK 메시지 클래스는 getter가 없어 리플렉션으로 검증한다. ApnsConfig.payload는 "aps" 키에 Aps 필드 맵을 담는다.
    @Test
    @SuppressWarnings("unchecked")
    void 발송_메시지는_iOS_기본_사운드용_ApnsConfig를_포함한다() throws Exception {
        MulticastMessage message = FirebaseFcmSender.buildMessage(tokens(1), "제목", "본문");

        ApnsConfig apnsConfig = (ApnsConfig) readField(message, MulticastMessage.class, "apnsConfig");
        assertThat(apnsConfig).isNotNull();

        Map<String, Object> payload = (Map<String, Object>) readField(apnsConfig, ApnsConfig.class, "payload");
        Map<String, Object> aps = (Map<String, Object>) payload.get("aps");
        assertThat(aps).containsEntry("sound", "default");
    }

    private static Object readField(Object target, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
