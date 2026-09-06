package com.triples.rougether.userapi.billing.store;

import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.*;

public interface StorePurchaseVerifier {
    record Verified(Store store, Environment environment, String reference, String productId,
                    String accountToken, int quantity, int refundableQuantity, boolean consumed, Long signedAt) {
        public Verified {
            if (store == null || environment == null || reference == null || reference.isBlank()
                    || reference.length() > 4096 || productId == null || productId.isBlank() || productId.length() > 200
                    || accountToken == null || !accountToken.matches("[a-fA-F0-9-]{36}")
                    || quantity < 1 || quantity > 100 || refundableQuantity < 0 || refundableQuantity > quantity
                    || (store == Store.APPLE && (signedAt == null || signedAt <= 0)))
                throw new IllegalArgumentException("유효하지 않은 검증 거래");
        }
    }
    Store store();
    boolean available();
    default void validateConfiguration() { }
    Verified verify(String reference);
    default void consume(Verified purchase) { }
}
