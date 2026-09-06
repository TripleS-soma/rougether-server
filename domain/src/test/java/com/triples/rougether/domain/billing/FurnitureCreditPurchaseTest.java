package com.triples.rougether.domain.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.Environment;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.Store;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FurnitureCreditPurchaseTest {
    private final Instant now = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void Google_다중수량_부분환불과_역순알림을_한번씩_반영() {
        var purchase = purchase(Store.GOOGLE, 3, 2);
        assertThat(purchase.synchronize(2, false, null, now)).isEqualTo(6);
        assertThat(purchase.synchronize(1, true, null, now)).isEqualTo(-3);
        assertThat(purchase.synchronize(2, false, null, now)).isZero();
        assertThat(purchase.synchronize(0, true, null, now)).isEqualTo(-3);
        assertThat(purchase.synchronize(0, true, null, now)).isZero();
        assertThat(purchase.isStoreConsumed()).isTrue();
    }

    @Test
    void 취소된_거래가_먼저_와도_생성권을_만들지_않음() {
        var purchase = purchase(Store.GOOGLE, 7, 1);
        assertThat(purchase.synchronize(0, false, null, now)).isZero();
        assertThat(purchase.synchronize(1, false, null, now)).isZero();
    }

    @Test
    void Apple_환불후_재결정과_재환불은_서명시각_순서로_반영() {
        var purchase = purchase(Store.APPLE, 3, 1);
        assertThat(purchase.synchronize(1, true, 100L, now)).isEqualTo(3);
        assertThat(purchase.synchronize(0, true, 200L, now)).isEqualTo(-3);
        assertThat(purchase.synchronize(1, true, 100L, now)).isZero();
        assertThat(purchase.synchronize(1, true, 300L, now)).isEqualTo(3);
        assertThat(purchase.synchronize(0, true, 200L, now)).isZero();
        assertThat(purchase.synchronize(0, true, 400L, now)).isEqualTo(-3);
    }

    private FurnitureCreditPurchase purchase(Store store, int credits, int quantity) {
        return new FurnitureCreditPurchase(1L, store, Environment.SANDBOX, "hash", "encrypted",
                "pack", credits, quantity, now);
    }
}
