package com.triples.rougether.userapi.notification.fcm;

import com.google.firebase.FirebaseApp;
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

// firebase.credentials-path가 설정된 환경에서만 활성화됨(FirebaseConfig와 동일 조건 → FirebaseApp 빈 주입 보장).
// 프로필 무관 — 로컬도 자격증명을 주입하면 실제 FCM 발송으로 동작함.
@Slf4j
@ConditionalOnExpression("'${firebase.credentials-path:}' != ''")
@Component
@RequiredArgsConstructor
public class FirebaseFcmSender implements FcmSender {

    // Firebase MulticastMessage는 호출당 토큰을 최대 500개까지만 허용함(초과 시 addAllTokens에서 IllegalArgumentException).
    private static final int MULTICAST_MAX_TOKENS = 500;

    private final FirebaseApp firebaseApp;

    @Override
    public List<String> send(List<String> tokens, String title, String body) {
        if (tokens.isEmpty()) {
            return List.of();
        }

        List<String> invalidTokens = new ArrayList<>();
        for (List<String> chunk : partition(tokens, MULTICAST_MAX_TOKENS)) {
            invalidTokens.addAll(sendChunk(chunk, title, body));
        }
        return invalidTokens;
    }

    private List<String> sendChunk(List<String> tokens, String title, String body) {
        MulticastMessage message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .build();

        BatchResponse batchResponse;
        try {
            batchResponse = FirebaseMessaging.getInstance(firebaseApp).sendEachForMulticast(message);
        } catch (FirebaseMessagingException e) {
            log.warn("FCM 멀티캐스트 발송 실패", e);
            return List.of();
        }

        List<String> invalidTokens = new ArrayList<>();
        List<SendResponse> responses = batchResponse.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse response = responses.get(i);
            if (response.isSuccessful()) {
                continue;
            }
            MessagingErrorCode errorCode = response.getException().getMessagingErrorCode();
            if (errorCode == MessagingErrorCode.UNREGISTERED || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                invalidTokens.add(tokens.get(i));
            }
        }
        return invalidTokens;
    }

    static List<List<String>> partition(List<String> tokens, int size) {
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i += size) {
            chunks.add(tokens.subList(i, Math.min(i + size, tokens.size())));
        }
        return chunks;
    }
}
