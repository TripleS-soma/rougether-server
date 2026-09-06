package com.triples.rougether.userapi.billing.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.triples.rougether.common.error.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.*;

class GoogleBillingNotificationVerifierTest {
    private JwtDecoder decoder;
    private GoogleBillingNotificationVerifier verifier;
    @BeforeEach void setup() { decoder = mock(JwtDecoder.class); verifier = new GoogleBillingNotificationVerifier(BillingStoreFixtures.config(), decoder); }
    @Test void 서명과_전용_audience_발신계정을_검증() {
        when(decoder.decode("valid")).thenReturn(jwt("https://billing.example.test/notifications/google", "billing@example.iam.gserviceaccount.com"));
        assertThat(verifier.reference("Bearer valid", data())).isEqualTo("purchase-token");
        verify(decoder).decode("valid");
    }
    @Test void 일반_Google로그인토큰이나_다른서비스계정은_거부() {
        when(decoder.decode("wrong")).thenReturn(jwt("oauth-client-id", "billing@example.iam.gserviceaccount.com"));
        assertThatThrownBy(() -> verifier.reference("Bearer wrong", data())).isInstanceOf(BusinessException.class);
        when(decoder.decode("wrong")).thenReturn(jwt("https://billing.example.test/notifications/google", "attacker@example.test"));
        assertThatThrownBy(() -> verifier.reference("Bearer wrong", data())).isInstanceOf(BusinessException.class);
    }
    @Test void 인증누락과_서명실패는_내용을_신뢰하지_않음() {
        assertThatThrownBy(() -> verifier.reference(null, data())).isInstanceOf(BusinessException.class);
        when(decoder.decode("bad")).thenThrow(new JwtException("signature failed"));
        assertThatThrownBy(() -> verifier.reference("Bearer bad", data())).isInstanceOf(BusinessException.class).hasNoCause();
    }
    @Test void 일회성상품_환불만_처리하고_구독환불은_무시() {
        when(decoder.decode("valid")).thenReturn(jwt("https://billing.example.test/notifications/google", "billing@example.iam.gserviceaccount.com"));
        String oneTime = "{\"packageName\":\"com.triples.rougether\",\"voidedPurchaseNotification\":{\"productType\":2,\"purchaseToken\":\"voided-token\"}}";
        assertThat(verifier.reference("Bearer valid", Base64.getEncoder().encodeToString(oneTime.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("voided-token");
        String subscription = oneTime.replace("\"productType\":2", "\"productType\":1");
        assertThat(verifier.reference("Bearer valid", Base64.getEncoder().encodeToString(subscription.getBytes(StandardCharsets.UTF_8))))
                .isNull();
    }
    private Jwt jwt(String audience, String email) {
        return Jwt.withTokenValue("test").header("alg", "RS256").issuer("https://accounts.google.com")
                .audience(List.of(audience)).claim("email", email).claim("email_verified", true)
                .issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(60)).build();
    }
    private String data() {
        return Base64.getEncoder().encodeToString("{\"packageName\":\"com.triples.rougether\",\"oneTimeProductNotification\":{\"purchaseToken\":\"purchase-token\"}}".getBytes(StandardCharsets.UTF_8));
    }
}
