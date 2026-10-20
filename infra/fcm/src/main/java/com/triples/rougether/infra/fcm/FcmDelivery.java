package com.triples.rougether.infra.fcm;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 발송 결과만 판정함. 토큰 조회·삭제와 알림 상태 저장은 호출자가 기존 트랜잭션에서 수행함
@Component
@RequiredArgsConstructor
public class FcmDelivery {

    private final FcmSender fcmSender;

    // 토큰이 없으면 이동 정보를 만들지 않으며, 이동 정보 생성 실패도 발송 실패로 처리함
    public FcmDeliveryResult deliver(List<String> tokens, String title, String body,
                                     Supplier<Map<String, String>> dataSupplier) {
        if (tokens.isEmpty()) {
            return FcmDeliveryResult.noTokens();
        }

        FcmSendResult result;
        try {
            Map<String, String> data = dataSupplier.get();
            result = data.isEmpty() ? fcmSender.send(tokens, title, body)
                    : fcmSender.send(tokens, title, body, data);
        } catch (Exception e) {
            return FcmDeliveryResult.failed(e);
        }
        return FcmDeliveryResult.from(result);
    }
}
