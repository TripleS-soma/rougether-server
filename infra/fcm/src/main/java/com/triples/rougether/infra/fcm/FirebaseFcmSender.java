package com.triples.rougether.infra.fcm;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Slf4j
@ConditionalOnExpression("'${firebase.credentials-path:}' != ''")
@Component
@RequiredArgsConstructor
public class FirebaseFcmSender implements FcmSender {

    // Firebase MulticastMessage는 호출당 토큰을 최대 500개까지만 허용함(초과 시 addAllTokens에서 IllegalArgumentException).
    private static final int MULTICAST_MAX_TOKENS = 500;

    private final FirebaseApp firebaseApp;

    @Override
    public FcmSendResult send(List<String> tokens, String title, String body) {
        if (tokens.isEmpty()) {
            return FcmSendResult.empty();
        }

        int successCount = 0;
        List<String> invalidTokens = new ArrayList<>();
        for (List<String> chunk : partition(tokens, MULTICAST_MAX_TOKENS)) {
            FcmSendResult chunkResult = sendChunk(chunk, title, body);
            successCount += chunkResult.successCount();
            invalidTokens.addAll(chunkResult.invalidTokens());
        }
        return new FcmSendResult(successCount, invalidTokens);
    }

    private FcmSendResult sendChunk(List<String> tokens, String title, String body) {
        MulticastMessage message = buildMessage(tokens, title, body);

        BatchResponse batchResponse;
        try {
            batchResponse = FirebaseMessaging.getInstance(firebaseApp).sendEachForMulticast(message);
        } catch (FirebaseMessagingException e) {
            // 배치 전체 실패(인증·쿼터·네트워크 등) - 개별 토큰 성패를 알 수 없으므로 전부 실패로 집계하고
            // 무효 토큰 여부는 판단할 수 없으니 삭제 대상에는 포함하지 않는다.
            log.warn("FCM 멀티캐스트 발송 실패", e);
            return FcmSendResult.empty();
        }

        int successCount = 0;
        List<String> invalidTokens = new ArrayList<>();
        List<SendResponse> responses = batchResponse.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse response = responses.get(i);
            if (response.isSuccessful()) {
                successCount++;
                continue;
            }
            MessagingErrorCode errorCode = response.getException().getMessagingErrorCode();
            log.warn("FCM 개별 발송 실패 - errorCode={}, message={}", errorCode,
                    response.getException().getMessage());
            if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                invalidTokens.add(tokens.get(i));
            }
        }
        return new FcmSendResult(successCount, invalidTokens);
    }

    // iOS는 aps.sound를 명시하지 않으면 알림이 무음으로 도착함 — APNs 릴레이용 기본 사운드를 지정함.
    static MulticastMessage buildMessage(List<String> tokens, String title, String body) {
        return MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .setApnsConfig(ApnsConfig.builder()
                        .setAps(Aps.builder().setSound("default").build())
                        .build())
                .build();
    }

    static List<List<String>> partition(List<String> tokens, int size) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i += size) {
            chunks.add(tokens.subList(i, Math.min(i + size, tokens.size())));
        }
        return chunks;
    }
}
