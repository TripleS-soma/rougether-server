package com.triples.rougether.userapi.billing.store;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.Environment;
import com.triples.rougether.userapi.billing.config.BillingProperties;
import java.util.*;
final class BillingStoreFixtures {
    static final String ACCOUNT = "00000000-0000-4000-8000-000000000001";
    static BillingProperties config() {
        return new BillingProperties(true, true, Environment.SANDBOX, "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
                new BillingProperties.Apple(true, "com.triples.rougether", 123L, "test", "test", "/test/key", List.of("/test/root"), Map.of("pack3", 3)),
                new BillingProperties.Google(true, "com.triples.rougether", "/test/credentials",
                        "https://billing.example.test/notifications/google", "billing@example.iam.gserviceaccount.com", Map.of("pack3", 3)));
    }
}
