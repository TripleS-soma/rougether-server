package com.triples.rougether.userapi.wallet.service;

import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.member.repository.WalletHistoryRepository;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 재화 증감 원장 기록(#253). 모든 메서드는 지갑 증감과 같은 트랜잭션에서 호출돼야
// 원장-잔액 정합성이 보장됨 — MANDATORY 로 강제해 트랜잭션 밖 호출은 즉시 실패시킴.
@Component
public class WalletHistoryRecorder {

    private final WalletHistoryRepository walletHistoryRepository;

    public WalletHistoryRecorder(WalletHistoryRepository walletHistoryRepository) {
        this.walletHistoryRepository = walletHistoryRepository;
    }

    // 증감 직후의 지갑을 받아 잔액 스냅샷(balance_after)과 함께 기록함.
    // 증감액 0(일일 상한 도달, 초대 한도 초과 등)은 기록하지 않음(#253 결정값)
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UserWallet wallet, int amount, WalletHistoryReason reason,
                       String sourceType, Long sourceId) {
        if (amount == 0) {
            return;
        }
        walletHistoryRepository.save(WalletHistory.record(
                wallet.getUser(), wallet.getCurrencyType(), amount, reason,
                wallet.getBalance(), sourceType, sourceId));
    }

    // 루틴/투두 완료 취소 — 회수 row 를 남기지 않고 원 획득 row 를 삭제함(#253 결정값).
    // 보상 0 완료(과거 완료·상한 도달)는 애초에 row 가 없어 no-op
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteEarned(WalletHistoryReason reason, String sourceType, Long sourceId) {
        walletHistoryRepository.deleteByReasonAndSourceTypeAndSourceId(reason, sourceType, sourceId);
    }

    // 가입 보너스 — 신규 발급 지갑의 초기 잔액을 기록함. 초기 잔액 0 지갑(다이아)은 대상 아님
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSignupBonus(List<UserWallet> wallets) {
        wallets.stream()
                .filter(wallet -> wallet.getBalance() > 0)
                .forEach(wallet -> record(wallet, wallet.getBalance(),
                        WalletHistoryReason.SIGNUP_BONUS, null, null));
    }
}
