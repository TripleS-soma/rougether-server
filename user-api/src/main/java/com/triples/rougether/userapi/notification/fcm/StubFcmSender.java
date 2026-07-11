package com.triples.rougether.userapi.notification.fcm;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

// firebase.credentials-path 미설정 환경용 발송 stub
// 살제 발송X
@Slf4j
@ConditionalOnExpression("'${firebase.credentials-path:}' == ''")
@Component
public class StubFcmSender implements FcmSender {

    public StubFcmSender(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "prod에서 FCM stub이 활성화됨 — FIREBASE_CREDENTIALS_PATH가 빈 값인지 확인 필요");
        }
    }

    @Override
    public List<String> send(List<String> tokens, String title, String body) {
        log.debug("[stub] FCM 발송 생략 - tokens={}, title={}", tokens.size(), title);
        return List.of();
    }
}
