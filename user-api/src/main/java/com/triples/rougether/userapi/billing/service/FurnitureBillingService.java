package com.triples.rougether.userapi.billing.service;

import static com.triples.rougether.common.error.BillingErrorCode.*;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.Store;
import com.triples.rougether.userapi.billing.config.BillingProperties;
import com.triples.rougether.userapi.billing.store.StorePurchaseVerifier;
import com.triples.rougether.userapi.billing.store.ApplePurchaseVerifier;
import com.triples.rougether.userapi.billing.store.GooglePurchaseVerifier;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class FurnitureBillingService {
    private final FurnitureCreditTransactions transactions;
    private final PurchaseReferenceCipher cipher;
    private final BillingProperties config;
    private final Map<Store, StorePurchaseVerifier> verifiers;
    public FurnitureBillingService(FurnitureCreditTransactions transactions, PurchaseReferenceCipher cipher,
            BillingProperties config, ApplePurchaseVerifier apple, GooglePurchaseVerifier google) {
        this.transactions = transactions; this.cipher = cipher; this.config = config;
        this.verifiers = Map.of(Store.APPLE, apple, Store.GOOGLE, google);
    }
    public record Product(String productId, int credits) { }
    public record Products(boolean available, List<Product> items) { }
    public Products products(Store store) {
        boolean available = config.enabled() && verifier(store).available() && !config.products(store).isEmpty();
        if (available) {
            try { verifier(store).validateConfiguration(); }
            catch (BusinessException e) { available = false; }
        }
        return new Products(available, available ? config.products(store).entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(e -> new Product(e.getKey(), e.getValue())).toList() : List.of());
    }
    public FurnitureCreditTransactions.Receipt confirm(Long userId, Store store, String reference) {
        if (!config.enabled() || !verifier(store).available()) throw new BusinessException(BILLING_UNAVAILABLE);
        var existing = transactions.prepare(userId, store, PurchaseReferenceCipher.hash(reference));
        if (existing != null) return existing;
        var verified = verifier(store).verify(reference);
        requireMatchingReference(store, reference, verified);
        return transactions.apply(userId, verified, cipher.encrypt(reference));
    }
    public void reconcile(Store store, String reference) {
        var verified = verifier(store).verify(reference);
        requireMatchingReference(store, reference, verified);
        try { transactions.apply(null, verified, cipher.encrypt(reference)); }
        catch (BusinessException e) {
            // 같은 앱의 다른 상품 알림은 생성권으로 지급하지 않음.
            if (!e.getErrorCode().code().equals(BILLING_PRODUCT_UNAVAILABLE.code())) throw e;
        }
    }
    public void consumePending() {
        for (String id : transactions.dueConsumptions()) {
            try {
                var claim = transactions.claimConsumption(id);
                if (claim == null) continue;
                var verifier = verifier(claim.store());
                String reference = cipher.decrypt(claim.encryptedReference());
                var verified = verifier.verify(reference);
                requireMatchingReference(claim.store(), reference, verified);
                transactions.apply(null, verified, claim.encryptedReference());
                verifier.consume(verified);
                transactions.consumed(id);
            } catch (RuntimeException e) { log.warn("생성권 스토어 소비 확인 보류 purchase={}", id); }
        }
    }
    private StorePurchaseVerifier verifier(Store store) {
        var verifier = verifiers.get(store);
        if (verifier == null) throw new BusinessException(BILLING_UNAVAILABLE);
        return verifier;
    }
    private void requireMatchingReference(Store store, String reference, StorePurchaseVerifier.Verified verified) {
        if (verified.store() != store || !verified.reference().equals(reference))
            throw new BusinessException(BILLING_PURCHASE_INVALID);
    }
}
