package com.triples.rougether.infra.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FcmDeliveryTest {

    private final RecordingSender sender = new RecordingSender();
    private final FcmDelivery delivery = new FcmDelivery(sender);

    @Test
    void 토큰이_없으면_이동_정보_생성과_발송을_모두_생략한다() {
        FcmDeliveryResult result = delivery.deliver(List.of(), "제목", "본문", () -> {
            throw new AssertionError("토큰이 없으면 호출되면 안 됨");
        });

        assertThat(result.status()).isEqualTo(FcmDeliveryResult.Status.NO_TOKENS);
        assertThat(result.successful()).isFalse();
        assertThat(result.invalidTokens()).isEmpty();
        assertThat(sender.calls).isZero();
    }

    @Test
    void 일부_기기만_성공해도_성공이며_무효_토큰_목록은_그대로_전달한다() {
        sender.result = new FcmSendResult(1, List.of("dead"));

        FcmDeliveryResult result = delivery.deliver(List.of("alive", "dead"), "제목", "본문", Map::of);

        assertThat(result.successful()).isTrue();
        assertThat(result.invalidTokens()).containsExactly("dead");
        assertThat(sender.calls).isEqualTo(1);
        assertThat(sender.data).isNull();
    }

    @Test
    void 성공이_없으면_무효_토큰_유무와_관계없이_실패한다() {
        for (List<String> invalid : List.of(List.<String>of(), List.of("dead"))) {
            sender.result = new FcmSendResult(0, invalid);

            FcmDeliveryResult result = delivery.deliver(List.of("dead"), "제목", "본문", Map::of);

            assertThat(result.status()).isEqualTo(FcmDeliveryResult.Status.NO_SUCCESS);
            assertThat(result.successful()).isFalse();
            assertThat(result.invalidTokens()).isEqualTo(invalid);
        }
    }

    @Test
    void 이동_정보가_있으면_제목과_본문_토큰까지_그대로_전달한다() {
        Map<String, String> data = Map.of("screen", "myRoom", "notificationId", "42");

        delivery.deliver(List.of("token"), "제목", "본문", () -> data);

        assertThat(sender.calls).isEqualTo(1);
        assertThat(sender.tokens).containsExactly("token");
        assertThat(sender.title).isEqualTo("제목");
        assertThat(sender.body).isEqualTo("본문");
        assertThat(sender.data).isEqualTo(data);
    }

    @Test
    void 발송_예외는_원인을_보존하고_토큰을_삭제_대상으로_만들지_않는다() {
        sender.failure = new IllegalStateException("FCM unavailable");

        FcmDeliveryResult result = delivery.deliver(List.of("token"), "제목", "본문", Map::of);

        assertThat(result.status()).isEqualTo(FcmDeliveryResult.Status.SEND_FAILED);
        assertThat(result.cause()).isSameAs(sender.failure);
        assertThat(result.invalidTokens()).isEmpty();
    }

    @Test
    void 이동_정보_생성이_실패하면_원인을_보존하고_발송하지_않는다() {
        IllegalArgumentException failure = new IllegalArgumentException("invalid payload");

        FcmDeliveryResult result = delivery.deliver(List.of("token"), "제목", "본문", () -> {
            throw failure;
        });

        assertThat(result.status()).isEqualTo(FcmDeliveryResult.Status.SEND_FAILED);
        assertThat(result.cause()).isSameAs(failure);
        assertThat(result.invalidTokens()).isEmpty();
        assertThat(sender.calls).isZero();
    }

    @Test
    void 발송_결과_해석_오류는_발송_예외로_삼키지_않는다() {
        sender.result = null;

        assertThatThrownBy(() -> delivery.deliver(List.of("token"), "제목", "본문", Map::of))
                .isInstanceOf(NullPointerException.class);
    }

    private static class RecordingSender implements FcmSender {
        private FcmSendResult result = new FcmSendResult(1, List.of());
        private RuntimeException failure;
        private int calls;
        private List<String> tokens;
        private String title;
        private String body;
        private Map<String, String> data;

        @Override
        public FcmSendResult send(List<String> tokens, String title, String body) {
            return send(tokens, title, body, null);
        }

        @Override
        public FcmSendResult send(List<String> tokens, String title, String body, Map<String, String> data) {
            calls++;
            this.tokens = tokens;
            this.title = title;
            this.body = body;
            this.data = data;
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
