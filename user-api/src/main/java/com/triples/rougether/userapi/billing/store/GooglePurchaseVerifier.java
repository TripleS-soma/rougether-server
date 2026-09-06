package com.triples.rougether.userapi.billing.store;

import static com.triples.rougether.userapi.billing.error.BillingErrorCode.*;
import com.google.auth.oauth2.GoogleCredentials;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.*;
import com.triples.rougether.userapi.billing.config.BillingProperties;
import java.io.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GooglePurchaseVerifier implements StorePurchaseVerifier {
    private final BillingProperties config;
    private final RestClient http;
    private final Supplier<String> accessToken;
    private GoogleCredentials credentials;
    @Autowired
    public GooglePurchaseVerifier(BillingProperties config) {
        this.config = config;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        http = RestClient.builder().baseUrl("https://androidpublisher.googleapis.com").requestFactory(factory).build();
        accessToken = this::token;
    }
    GooglePurchaseVerifier(BillingProperties config, RestClient http, Supplier<String> accessToken) {
        this.config = config; this.http = http; this.accessToken = accessToken;
    }
    public Store store() { return Store.GOOGLE; }
    public boolean available() {
        return config.google().enabled() && !config.google().packageName().isBlank() && !config.google().credentialsPath().isBlank();
    }
    private synchronized String token() {
        try {
            validateConfiguration();
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException | RuntimeException e) { throw new BusinessException(BILLING_UNAVAILABLE); }
    }
    public synchronized void validateConfiguration() {
        if (!available()) throw new BusinessException(BILLING_UNAVAILABLE);
        if (credentials != null) return;
        try (var stream = Files.newInputStream(Path.of(config.google().credentialsPath()))) {
            credentials = GoogleCredentials.fromStream(stream).createScoped("https://www.googleapis.com/auth/androidpublisher");
        } catch (IOException | RuntimeException e) { throw new BusinessException(BILLING_UNAVAILABLE); }
    }
    public Verified verify(String reference) {
        if (!available()) throw new BusinessException(BILLING_UNAVAILABLE);
        if (reference == null || reference.isBlank() || reference.length() > 4096) throw new BusinessException(BILLING_PURCHASE_INVALID);
        try {
            JsonNode payload = http.get()
                    .uri("/androidpublisher/v3/applications/{package}/purchases/productsv2/tokens/{token}", config.google().packageName(), reference)
                    .headers(headers -> headers.setBearerAuth(accessToken.get()))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful())
                            throw new BusinessException(response.getStatusCode().value() == 404 ? BILLING_PURCHASE_INVALID : BILLING_UNAVAILABLE);
                        byte[] bytes = response.getBody().readNBytes(262145);
                        if (bytes.length > 262144) throw new BusinessException(BILLING_UNAVAILABLE);
                        return JsonMapper.builder().build().readTree(new String(bytes, StandardCharsets.UTF_8));
                    });
            String state = payload.path("purchaseStateContext").path("purchaseState").asString();
            if (state.equals("PENDING")) throw new BusinessException(BILLING_PURCHASE_PENDING);
            if (!List.of("PURCHASED", "CANCELLED").contains(state)) throw new BusinessException(BILLING_PURCHASE_INVALID);
            Environment environment = payload.hasNonNull("testPurchaseContext") ? Environment.SANDBOX : Environment.PRODUCTION;
            if (environment != config.environment()) throw new BusinessException(BILLING_PURCHASE_INVALID);
            JsonNode lines = payload.path("productLineItem");
            if (!lines.isArray() || lines.size() != 1) throw new BusinessException(BILLING_PURCHASE_INVALID);
            JsonNode line = lines.get(0), offer = line.path("productOfferDetails");
            if (offer.hasNonNull("rentOfferDetails")) throw new BusinessException(BILLING_PURCHASE_INVALID);
            if (!offer.path("quantity").isIntegralNumber()) throw new BusinessException(BILLING_PURCHASE_INVALID);
            int quantity = offer.path("quantity").asInt();
            if (!offer.path("refundableQuantity").isIntegralNumber()) throw new BusinessException(BILLING_PURCHASE_INVALID);
            if (!List.of("CONSUMPTION_STATE_CONSUMED", "CONSUMPTION_STATE_YET_TO_BE_CONSUMED")
                    .contains(offer.path("consumptionState").asString())) throw new BusinessException(BILLING_PURCHASE_INVALID);
            return new Verified(store(), environment, reference, line.path("productId").asString(),
                    payload.path("obfuscatedExternalAccountId").asString(), quantity,
                    state.equals("CANCELLED") ? 0 : offer.path("refundableQuantity").asInt(),
                    offer.path("consumptionState").asString().equals("CONSUMPTION_STATE_CONSUMED"), null);
        } catch (BusinessException e) { throw e; }
        catch (IllegalArgumentException e) { throw new BusinessException(BILLING_PURCHASE_INVALID); }
        catch (RuntimeException e) { throw new BusinessException(BILLING_UNAVAILABLE); }
    }
    public void consume(Verified purchase) {
        if (purchase.consumed() || purchase.refundableQuantity() == 0) return;
        try {
            http.post().uri("/androidpublisher/v3/applications/{package}/purchases/products/{product}/tokens/{token}:consume",
                            config.google().packageName(), purchase.productId(), purchase.reference())
                    .headers(headers -> headers.setBearerAuth(accessToken.get())).retrieve().toBodilessEntity();
        } catch (RuntimeException e) { throw new BusinessException(BILLING_UNAVAILABLE); }
    }
}
