package com.triples.rougether.userapi.billing.store;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.triples.rougether.common.error.BusinessException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class GooglePurchaseVerifierTest {
    private GooglePurchaseVerifier verifier;
    private MockRestServiceServer server;
    private final String url = "https://play.test/androidpublisher/v3/applications/com.triples.rougether/purchases/productsv2/tokens/test-token";
    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://play.test");
        server = MockRestServiceServer.bindTo(builder).build();
        verifier = new GooglePurchaseVerifier(BillingStoreFixtures.config(), builder.build(), () -> "server-only-access-token");
    }
    @AfterEach void verify() { server.verify(); }
    @Test void 스토어에서_확인한_계정과_부분환불_수량을_사용() {
        expect(payload("PURCHASED", 2, 1, BillingStoreFixtures.ACCOUNT, true));
        var result = verifier.verify("test-token");
        assertThat(result.quantity()).isEqualTo(2);
        assertThat(result.refundableQuantity()).isEqualTo(1);
        assertThat(result.productId()).isEqualTo("pack3");
        assertThat(result.accountToken()).isEqualTo(BillingStoreFixtures.ACCOUNT);
    }
    @Test void 미완료결제는_지급하지_않음() {
        expect(payload("PENDING", 1, 1, BillingStoreFixtures.ACCOUNT, true));
        assertCode("BILLING_PURCHASE_PENDING");
    }
    @Test void 계정연결이_없는_구매는_거부() {
        expect(payload("PURCHASED", 1, 1, "", true));
        assertCode("BILLING_PURCHASE_INVALID");
    }
    @Test void 스토어환경이_다른_구매는_거부() {
        expect(payload("PURCHASED", 1, 1, BillingStoreFixtures.ACCOUNT, false));
        assertCode("BILLING_PURCHASE_INVALID");
    }
    @Test void 취소된거래는_남은수량이_있어도_모두_회수() {
        expect(payload("CANCELLED", 2, 2, BillingStoreFixtures.ACCOUNT, true));
        assertThat(verifier.verify("test-token").refundableQuantity()).isZero();
    }
    @Test void 소비확인은_검증된상품과_토큰으로_서버에서_호출() {
        expect(payload("PURCHASED", 1, 1, BillingStoreFixtures.ACCOUNT, true));
        server.expect(requestTo("https://play.test/androidpublisher/v3/applications/com.triples.rougether/purchases/products/pack3/tokens/test-token:consume"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer server-only-access-token"))
                .andRespond(withNoContent());
        verifier.consume(verifier.verify("test-token"));
    }
    @Test void 공급자_오류가_영수증이나_시크릿을_노출하지_않음() {
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret purchase token"));
        assertThatThrownBy(() -> verifier.verify("test-token")).isInstanceOf(BusinessException.class)
                .hasMessage("현재 생성권을 구매할 수 없습니다.").hasNoCause();
    }
    private void expect(String body) {
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer server-only-access-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
    private String payload(String state, int quantity, int remaining, String account, boolean test) {
        var map = new HashMap<String, Object>();
        map.put("purchaseStateContext", Map.of("purchaseState", state));
        map.put("productLineItem", List.of(Map.of("productId", "pack3", "productOfferDetails",
                Map.of("quantity", quantity, "refundableQuantity", remaining, "consumptionState", "CONSUMPTION_STATE_YET_TO_BE_CONSUMED"))));
        map.put("obfuscatedExternalAccountId", account);
        if (test) map.put("testPurchaseContext", Map.of("fopType", "TEST"));
        return JsonMapper.builder().build().writeValueAsString(map);
    }
    private void assertCode(String code) {
        assertThatThrownBy(() -> verifier.verify("test-token")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
    }
}
