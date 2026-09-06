package com.triples.rougether.userapi.billing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.billing.config.BillingProperties;
import com.triples.rougether.userapi.billing.service.PurchaseReferenceCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class BillingSecurityTest {
    @Test
    void 결제토큰은_매번_다르게_암호화하고_변조된_암호문은_거부() {
        var cipher = new PurchaseReferenceCipher(BillingStoreFixtures.config());
        String first = cipher.encrypt("purchase-token");
        assertThat(first).isNotEqualTo(cipher.encrypt("purchase-token"));
        assertThat(cipher.decrypt(first)).isEqualTo("purchase-token");
        byte[] tampered = Base64.getDecoder().decode(first);
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(tampered)))
                .isInstanceOf(BusinessException.class).hasNoCause();
    }

    @Test
    void 암호화키나_생성권_사용조건이_없으면_판매를_활성화할수_없음() {
        var config = BillingStoreFixtures.config();
        assertThatThrownBy(() -> new BillingProperties(true, true, config.environment(), "",
                config.apple(), config.google())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BillingProperties(true, false, config.environment(), config.encryptionKey(),
                config.apple(), config.google())).isInstanceOf(IllegalArgumentException.class);
    }
}
