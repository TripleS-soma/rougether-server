package com.triples.rougether.userapi.billing.store;

import static com.triples.rougether.common.error.BillingErrorCode.*;
import com.apple.itunes.storekit.client.*;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.Type;
import com.apple.itunes.storekit.verification.*;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.*;
import com.triples.rougether.userapi.billing.config.BillingProperties;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ApplePurchaseVerifier implements StorePurchaseVerifier {
    private final BillingProperties config;
    private AppStoreServerAPIClient client;
    private SignedDataVerifier verifier;
    @Autowired
    public ApplePurchaseVerifier(BillingProperties config) { this.config = config; }
    ApplePurchaseVerifier(BillingProperties config, AppStoreServerAPIClient client, SignedDataVerifier verifier) {
        this.config = config; this.client = client; this.verifier = verifier;
    }
    public Store store() { return Store.APPLE; }
    public boolean available() {
        var apple = config.apple();
        return apple.enabled() && !apple.bundleId().isBlank() && !apple.privateKeyPath().isBlank()
                && !apple.keyId().isBlank() && !apple.issuerId().isBlank() && !apple.rootCertificates().isEmpty()
                && (config.environment() != Environment.PRODUCTION || apple.appAppleId() != null);
    }
    private synchronized void initialize() {
        if (!available()) throw new BusinessException(BILLING_UNAVAILABLE);
        if (client != null && verifier != null) return;
        var apple = config.apple();
        List<InputStream> roots = new ArrayList<>();
        try {
            for (String path : apple.rootCertificates()) roots.add(Files.newInputStream(Path.of(path)));
            var environment = com.apple.itunes.storekit.model.Environment.valueOf(config.environment().name());
            verifier = new SignedDataVerifier(new HashSet<>(roots), apple.bundleId(), apple.appAppleId(), environment, true);
            client = new AppStoreServerAPIClient(Files.readString(Path.of(apple.privateKeyPath())),
                    apple.keyId(), apple.issuerId(), apple.bundleId(), environment);
        } catch (IOException | RuntimeException e) { throw new BusinessException(BILLING_UNAVAILABLE); }
        finally { roots.forEach(stream -> { try { stream.close(); } catch (IOException ignored) { } }); }
    }
    public void validateConfiguration() { initialize(); }
    public Verified verify(String reference) {
        if (reference == null || !reference.matches("[0-9]{1,64}")) throw new BusinessException(BILLING_PURCHASE_INVALID);
        initialize();
        try {
            // 클라이언트가 제출한 오래된 JWS 대신 스토어의 현재 거래를 조회하고 서명·앱·환경을 검증함.
            var payload = verifier.verifyAndDecodeTransaction(client.getTransactionInfo(reference).getSignedTransactionInfo());
            if (!reference.equals(payload.getTransactionId())) throw new BusinessException(BILLING_PURCHASE_INVALID);
            return verified(payload);
        } catch (VerificationException | IllegalArgumentException e) { throw new BusinessException(BILLING_PURCHASE_INVALID); }
        catch (APIException e) {
            throw new BusinessException(e.getHttpStatusCode() == 404 ? BILLING_PURCHASE_INVALID : BILLING_UNAVAILABLE);
        } catch (IOException e) { throw new BusinessException(BILLING_UNAVAILABLE); }
        catch (BusinessException e) { throw e; }
        catch (RuntimeException e) { throw new BusinessException(BILLING_UNAVAILABLE); }
    }
    private Verified verified(JWSTransactionDecodedPayload payload) {
        if (payload.getType() != Type.CONSUMABLE || payload.getAppAccountToken() == null
                || payload.getQuantity() == null || payload.getEnvironment() == null
                || !config.apple().bundleId().equals(payload.getBundleId())
                || !config.environment().name().equals(payload.getEnvironment().name()))
            throw new BusinessException(BILLING_PURCHASE_INVALID);
        return new Verified(store(), config.environment(), payload.getTransactionId(), payload.getProductId(),
                payload.getAppAccountToken().toString(), payload.getQuantity(),
                payload.getRevocationDate() == null ? payload.getQuantity() : 0, true, payload.getSignedDate());
    }
    public String notificationReference(String signedPayload) {
        initialize();
        try {
            var notification = verifier.verifyAndDecodeNotification(signedPayload);
            if (notification.getData() == null || notification.getData().getSignedTransactionInfo() == null) return null;
            var transaction = verifier.verifyAndDecodeTransaction(notification.getData().getSignedTransactionInfo());
            if (transaction.getType() != Type.CONSUMABLE) return null;
            return verified(transaction).reference();
        } catch (VerificationException | IllegalArgumentException e) { throw new BusinessException(BILLING_NOTIFICATION_INVALID); }
        catch (BusinessException e) { throw e; }
        catch (RuntimeException e) { throw new BusinessException(BILLING_NOTIFICATION_INVALID); }
    }
}
