package com.triples.rougether.userapi.billing.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.apple.itunes.storekit.client.AppStoreServerAPIClient;
import com.apple.itunes.storekit.model.*;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import com.triples.rougether.common.error.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.*;

class ApplePurchaseVerifierTest {
    private AppStoreServerAPIClient client;
    private SignedDataVerifier signatures;
    private ApplePurchaseVerifier verifier;
    private JWSTransactionDecodedPayload transaction;
    @BeforeEach void setup() throws Exception {
        client = mock(AppStoreServerAPIClient.class); signatures = mock(SignedDataVerifier.class);
        verifier = new ApplePurchaseVerifier(BillingStoreFixtures.config(), client, signatures);
        transaction = new JWSTransactionDecodedPayload().transactionId("12345").productId("pack3")
                .bundleId("com.triples.rougether").type(Type.CONSUMABLE).quantity(1)
                .environment(Environment.SANDBOX).signedDate(1788739200000L)
                .appAccountToken(UUID.fromString(BillingStoreFixtures.ACCOUNT));
        when(client.getTransactionInfo("12345")).thenReturn(new TransactionInfoResponse().signedTransactionInfo("fresh-store-jws"));
        when(signatures.verifyAndDecodeTransaction("fresh-store-jws")).thenReturn(transaction);
    }
    @Test void 현재거래_API응답의_서명을_검증한_정보만_사용() throws Exception {
        assertThat(verifier.verify("12345").refundableQuantity()).isEqualTo(1);
        verify(client).getTransactionInfo("12345");
        verify(signatures).verifyAndDecodeTransaction("fresh-store-jws");
    }
    @Test void 취소된_거래는_생성권_지급대상이_아님() {
        transaction.revocationDate(123L);
        assertThat(verifier.verify("12345").refundableQuantity()).isZero();
    }
    @Test void 순서를_검증할_서명시각이_없으면_거래를_거부() {
        transaction.signedDate(null);
        assertThatThrownBy(() -> verifier.verify("12345")).isInstanceOf(BusinessException.class);
    }
    @Test void 다른앱과_계정없는_거래를_거부() {
        transaction.bundleId("another.app");
        assertThatThrownBy(() -> verifier.verify("12345")).isInstanceOf(BusinessException.class);
        transaction.bundleId("com.triples.rougether").appAccountToken(null);
        assertThatThrownBy(() -> verifier.verify("12345")).isInstanceOf(BusinessException.class);
    }
    @Test void 다른_거래ID의_응답이나_서명검증실패는_지급하지_않음() throws Exception {
        transaction.transactionId("54321");
        assertThatThrownBy(() -> verifier.verify("12345")).isInstanceOf(BusinessException.class);
        when(signatures.verifyAndDecodeTransaction("fresh-store-jws")).thenThrow(new IllegalArgumentException("secret body"));
        assertThatThrownBy(() -> verifier.verify("12345")).isInstanceOf(BusinessException.class).hasNoCause();
    }
    @Test void 알림_서명이_잘못되면_거래조회도_실행하지_않음() throws Exception {
        when(signatures.verifyAndDecodeNotification("forged")).thenThrow(new IllegalArgumentException("bad signature"));
        assertThatThrownBy(() -> verifier.notificationReference("forged")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client);
    }
}
