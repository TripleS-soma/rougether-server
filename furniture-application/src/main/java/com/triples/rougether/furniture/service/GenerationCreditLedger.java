package com.triples.rougether.furniture.service;

import com.triples.rougether.common.error.*;
import com.triples.rougether.domain.billing.entity.*;
import com.triples.rougether.domain.billing.repository.*;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

// API 예약과 워커 정산은 사용자 잠금 및 기존 작업 트랜잭션에 참여함.
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class GenerationCreditLedger {
    private final UserRepository users;
    private final FurnitureCreditAccountRepository accounts;
    private final FurnitureCreditReservationRepository reservations;
    private final FurnitureCreditEntryRepository entries;
    private final Clock clock;
    private final boolean requireCredits;
    public GenerationCreditLedger(UserRepository users, FurnitureCreditAccountRepository accounts,
            FurnitureCreditReservationRepository reservations, FurnitureCreditEntryRepository entries,
            Clock clock, @Value("${billing.require-credits:true}") boolean requireCredits) {
        this.users=users; this.accounts=accounts; this.reservations=reservations;
        this.entries=entries; this.clock=clock; this.requireCredits=requireCredits;
    }
    public void reserve(Long userId, String jobId) {
        if (!requireCredits) return;
        var user=users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        if (user.getDeletedAt()!=null) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        if (reservations.existsById(jobId)) return;
        var account=account(userId);
        if (account.getBalance()<1) throw new BusinessException(BillingErrorCode.FURNITURE_CREDITS_REQUIRED);
        account.reserve(); reservations.save(new FurnitureCreditReservation(jobId,userId,clock.instant()));
        record(account,jobId,FurnitureCreditEntry.Reason.RESERVE,-1);
    }
    public void settle(Long userId, String jobId, boolean success) {
        users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        var reservation=reservations.findById(jobId).orElse(null);
        if (reservation==null || reservation.getStatus()!=FurnitureCreditReservation.Status.RESERVED) return;
        if (!reservation.getUserId().equals(userId)) throw new IllegalStateException("생성권 작업 소유자 불일치");
        var account=account(userId); account.settle(success); reservation.settle(success,clock.instant());
        record(account,jobId,success ? FurnitureCreditEntry.Reason.SPEND : FurnitureCreditEntry.Reason.RELEASE,success?0:1);
    }
    private FurnitureCreditAccount account(Long id) {
        return accounts.findForUpdate(id).orElseGet(() -> accounts.save(new FurnitureCreditAccount(id)));
    }
    private void record(FurnitureCreditAccount a,String id,FurnitureCreditEntry.Reason reason,long delta) {
        entries.save(new FurnitureCreditEntry(a.getUserId(),id,reason,delta,a.getBalance(),clock.instant()));
    }
}
