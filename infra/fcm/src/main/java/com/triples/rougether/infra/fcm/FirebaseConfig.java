package com.triples.rougether.infra.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.FileInputStream;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// firebase 서비스 계정 JSON은 환경변수/외부 경로로 주입하고 커밋하지 않음(firebase.credentials-path).
// 프로필이 아니라 credentials-path 설정 여부로 활성화됨 — 로컬도 이 값을 채우면 실제 FCM이 나가고,
// 비워두면(기본값) FcmSender stub 구현체(StubFcmSender)로 자동 대체됨.
@ConditionalOnExpression("'${firebase.credentials-path:}' != ''")
@Configuration
public class FirebaseConfig {

    @Bean
    public FirebaseApp firebaseApp(@Value("${firebase.credentials-path}") String credentialsPath) throws IOException {
        try (FileInputStream credentialsStream = new FileInputStream(credentialsPath)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credentialsStream))
                    .build();
            return FirebaseApp.initializeApp(options);
        }
    }
}
