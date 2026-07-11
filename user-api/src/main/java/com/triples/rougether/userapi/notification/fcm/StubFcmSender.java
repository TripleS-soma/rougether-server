package com.triples.rougether.userapi.notification.fcm;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// 로컬·테스트 프로필용 발송 stub. firebase 서비스 계정 JSON 없이 부팅 가능해야 하므로 실제 발송을 하지 않음.
@Slf4j
@Profile("!prod")
@Component
public class StubFcmSender implements FcmSender {

    @Override
    public List<String> send(List<String> tokens, String title, String body) {
        log.debug("[stub] FCM 발송 생략 - tokens={}, title={}", tokens.size(), title);
        return List.of();
    }
}
