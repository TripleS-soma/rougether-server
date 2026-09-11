package com.triples.rougether.userapi.billing.store;

import static com.triples.rougether.common.error.BillingErrorCode.*;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.billing.config.BillingProperties;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GoogleBillingNotificationVerifier {
    private final BillingProperties config;
    private final JwtDecoder decoder;
    @Autowired
    public GoogleBillingNotificationVerifier(BillingProperties config) {
        this(config, NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build());
    }
    GoogleBillingNotificationVerifier(BillingProperties config, JwtDecoder decoder) { this.config = config; this.decoder = decoder; }
    public String reference(String authorization, String data) {
        if (!config.google().enabled() || config.google().notificationAudience().isBlank()
                || config.google().notificationServiceAccount().isBlank()) throw new BusinessException(BILLING_UNAVAILABLE);
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 8192)
            throw new BusinessException(BILLING_NOTIFICATION_INVALID);
        try {
            Jwt jwt = decoder.decode(authorization.substring(7));
            if (!List.of("https://accounts.google.com", "accounts.google.com").contains(jwt.getClaimAsString("iss"))
                    || !jwt.getAudience().contains(config.google().notificationAudience())
                    || !config.google().notificationServiceAccount().equals(jwt.getClaimAsString("email"))
                    || !Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")))
                throw new BusinessException(BILLING_NOTIFICATION_INVALID);
            var payload = JsonMapper.builder().build().readTree(new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8));
            if (!config.google().packageName().equals(payload.path("packageName").asString()))
                throw new BusinessException(BILLING_NOTIFICATION_INVALID);
            String token = payload.path("oneTimeProductNotification").path("purchaseToken").asString("");
            var voided = payload.path("voidedPurchaseNotification");
            if (token.isBlank() && voided.path("productType").asInt() == 2)
                token = voided.path("purchaseToken").asString("");
            if (token.length() > 4096) throw new BusinessException(BILLING_NOTIFICATION_INVALID);
            return token.isBlank() ? null : token;
        } catch (BusinessException e) { throw e; }
        catch (RuntimeException e) { throw new BusinessException(BILLING_NOTIFICATION_INVALID); }
    }
}
