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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// firebase 서비스 계정 JSON이 실제로 주입되는 prod에서만 활성화됨(FirebaseConfig도 동일 프로필 조건).
@Slf4j
@Profile("prod")
@Component
@RequiredArgsConstructor
public class FirebaseFcmSender implements FcmSender {

    private final FirebaseApp firebaseApp;

    @Override
    public List<String> send(List<String> tokens, String title, String body) {
        if (tokens.isEmpty()) {
            return List.of();
        }

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
}
