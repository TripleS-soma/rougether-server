package com.triples.rougether.userapi.billing.service;

import static com.triples.rougether.common.error.BillingErrorCode.*;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.billing.entity.*;
import com.triples.rougether.domain.billing.entity.FurnitureCreditEntry.Reason;
import com.triples.rougether.domain.billing.entity.FurnitureCreditPurchase.*;
import com.triples.rougether.furniture.service.GenerationCreditLedger;
import com.triples.rougether.domain.billing.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.userapi.billing.config.BillingProperties;
import com.triples.rougether.userapi.billing.store.StorePurchaseVerifier.Verified;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

// 결제·가구 작업 모두 사용자 행을 먼저 잠금. 스토어 HTTP 호출은 트랜잭션 밖에서 수행함.
@Service
@RequiredArgsConstructor
@Transactional
public class FurnitureCreditTransactions {
    private final UserRepository users;
    private final FurnitureCreditAccountRepository accounts;
    private final FurnitureCreditPurchaseRepository purchases;
    private final FurnitureCreditEntryRepository entries;
    private final BillingProperties config;
    private final Clock clock;
    private final GenerationCreditLedger generationCredits;

    public record Balance(long available, long reserved, boolean purchaseAdjustmentPending, String accountToken) { }
    public record Receipt(String id, long credits, long revokedCredits, boolean storeConsumed, Balance balance) { }
    public record Consumption(String id, Store store, String encryptedReference) { }

    public Balance balance(Long userId) { activeUser(userId); return response(account(userId)); }

    @Transactional(propagation = Propagation.MANDATORY)
    public void grantAttendance(Long userId, Long eventId) {
        activeUser(userId);
        String reference = "attendance:" + eventId;
        if (entries.existsByUserIdAndReferenceIdAndReason(userId, reference, Reason.ATTENDANCE_REWARD)) return;
        var account = account(userId);
        account.adjust(1);
        record(account, reference, Reason.ATTENDANCE_REWARD, 1);
    }

    public Receipt prepare(Long userId, Store store, String hash) {
        activeUser(userId);
        var account = account(userId);
        var existing = purchases.findByStoreAndEnvironmentAndReferenceHash(store, config.environment(), hash);
        if (existing.isPresent()) {
            if (!existing.get().getUserId().equals(userId)) throw new BusinessException(BILLING_PURCHASE_OWNER_MISMATCH);
            return receipt(existing.get(), account);
        }
        if (account.getLastVerificationAt() != null
                && account.getLastVerificationAt().plusSeconds(2).isAfter(clock.instant()))
            throw new BusinessException(BILLING_VERIFICATION_LIMIT);
        account.verifiedAt(clock.instant());
        return null;
    }

    public Receipt apply(Long expectedUserId, Verified verified, String encryptedReference) {
        Long userId = accounts.findUserIdByAccountToken(verified.accountToken())
                .orElseThrow(() -> new BusinessException(BILLING_PURCHASE_OWNER_MISMATCH));
        if (expectedUserId != null && !expectedUserId.equals(userId))
            throw new BusinessException(BILLING_PURCHASE_OWNER_MISMATCH);
        User user = lockedUser(userId);
        if (verified.environment() != config.environment()) throw new BusinessException(BILLING_PURCHASE_INVALID);
        var account = account(userId);
        var existing = purchases.findByStoreAndEnvironmentAndReferenceHash(verified.store(), verified.environment(),
                PurchaseReferenceCipher.hash(verified.reference()));
        var purchase = existing.orElseGet(() -> {
            if (user.getDeletedAt() != null) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
            Integer credits = config.products(verified.store()).get(verified.productId());
            if (credits == null) throw new BusinessException(BILLING_PRODUCT_UNAVAILABLE);
            return purchases.save(new FurnitureCreditPurchase(userId, verified.store(), verified.environment(),
                    PurchaseReferenceCipher.hash(verified.reference()), encryptedReference, verified.productId(),
                    credits, verified.quantity(), clock.instant()));
        });
        if (!purchase.getUserId().equals(userId)) throw new BusinessException(BILLING_PURCHASE_OWNER_MISMATCH);
        if (!purchase.getProductId().equals(verified.productId()) || purchase.getQuantity() != verified.quantity())
            throw new BusinessException(BILLING_PURCHASE_INVALID);
        boolean previouslyGranted = purchase.getGrantedCredits() > 0;
        long delta = purchase.synchronize(verified.refundableQuantity(), verified.consumed(), verified.signedAt(), clock.instant());
        if (delta != 0) {
            account.adjust(delta);
            Reason reason = delta < 0 ? Reason.STORE_REFUND
                    : previouslyGranted ? Reason.STORE_REFUND_REVERSED : Reason.PURCHASE;
            record(account, purchase.getId(), reason, delta);
        }
        return receipt(purchase, account);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(Long userId, String jobId) {
        generationCredits.reserve(userId, jobId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(Long userId, String jobId, boolean success) {
        generationCredits.settle(userId, jobId, success);
    }

    @Transactional(readOnly = true)
    public List<String> dueConsumptions() { return purchases.findDue(clock.instant(), PageRequest.of(0, 20)); }

    public Consumption claimConsumption(String id) {
        lockedUser(purchases.findOwnerId(id).orElseThrow());
        var purchase = purchases.findForUpdate(id).orElseThrow();
        if (purchase.isStoreConsumed() || purchase.getNextConsumeAt().isAfter(clock.instant())) return null;
        purchase.deferConsumption(clock.instant().plusSeconds(120));
        return new Consumption(id, purchase.getStore(), purchase.getReferenceEncrypted());
    }

    public void consumed(String id) {
        lockedUser(purchases.findOwnerId(id).orElseThrow());
        purchases.findForUpdate(id).orElseThrow().consumed(clock.instant());
    }

    private FurnitureCreditAccount account(Long userId) {
        return accounts.findForUpdate(userId).orElseGet(() -> accounts.save(new FurnitureCreditAccount(userId)));
    }
    private User lockedUser(Long userId) {
        return users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
    }
    private void activeUser(Long userId) {
        if (lockedUser(userId).getDeletedAt() != null) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
    }
    private Balance response(FurnitureCreditAccount account) {
        return new Balance(Math.max(0, account.getBalance()), account.getReserved(), account.getBalance() < 0, account.getAccountToken());
    }
    private Receipt receipt(FurnitureCreditPurchase purchase, FurnitureCreditAccount account) {
        return new Receipt(purchase.getId(), purchase.getGrantedCredits(), purchase.getRevokedCredits(),
                purchase.isStoreConsumed(), response(account));
    }
    private void record(FurnitureCreditAccount account, String id, Reason reason, long delta) {
        entries.save(new FurnitureCreditEntry(account.getUserId(), id, reason, delta, account.getBalance(), clock.instant()));
    }
}
