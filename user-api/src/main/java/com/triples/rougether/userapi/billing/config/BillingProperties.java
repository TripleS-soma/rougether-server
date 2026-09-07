package com.triples.rougether.userapi.billing.config;

import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.Environment;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.Store;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("billing")
public record BillingProperties(@DefaultValue("false") boolean enabled, @DefaultValue("true") boolean requireCredits,
        @DefaultValue("PRODUCTION") Environment environment, @DefaultValue("") String encryptionKey,
        @DefaultValue Apple apple, @DefaultValue Google google) {
    public BillingProperties {
        if (enabled) {
            if (!requireCredits) throw new IllegalArgumentException("생성권 판매를 켜기 전에 생성권 사용 조건을 활성화해야 함");
            try {
                if (Base64.getDecoder().decode(encryptionKey).length != 32) throw new IllegalArgumentException();
            } catch (RuntimeException e) { throw new IllegalArgumentException("결제 토큰 암호화 키는 Base64로 인코딩한 32바이트여야 함"); }
        }
    }
    public record Apple(@DefaultValue("false") boolean enabled, @DefaultValue("") String bundleId,
            Long appAppleId, @DefaultValue("") String keyId, @DefaultValue("") String issuerId,
            @DefaultValue("") String privateKeyPath, List<String> rootCertificates, Map<String, Integer> products) {
        public Apple { products = checkedProducts(products); rootCertificates = rootCertificates == null ? List.of() : List.copyOf(rootCertificates); }
    }
    public record Google(@DefaultValue("false") boolean enabled, @DefaultValue("") String packageName,
            @DefaultValue("") String credentialsPath, @DefaultValue("") String notificationAudience,
            @DefaultValue("") String notificationServiceAccount, Map<String, Integer> products) {
        public Google { products = checkedProducts(products); }
    }
    public Map<String, Integer> products(Store store) { return store == Store.APPLE ? apple.products() : google.products(); }
    private static Map<String, Integer> checkedProducts(Map<String, Integer> products) {
        if (products == null) return Map.of();
        products.forEach((id, credits) -> {
            if (!id.matches("[a-zA-Z0-9._-]{1,200}") || credits == null || credits < 1 || credits > 1000)
                throw new IllegalArgumentException("결제 상품 ID 또는 생성권 수량 설정 오류");
        });
        return Map.copyOf(products);
    }
}
