package com.triples.rougether.userapi.routine.reward.service;

import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 완료 트랜잭션 안에서 보상액을 계산하고 코인·원장만 지급함. 상태 변경·성장은 호출자가 처리함.
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class CompletionRewardService {

    private static final int REWARD_AMOUNT = 10;

    private final DailyRewardService dailyRewardService;
    private final WalletHistoryRecorder walletHistoryRecorder;

    // 사용자·코인 지갑 잠금 뒤, 이번 완료의 지급액이 합계에 포함되기 전에 계산함.
    public int calculateAmount(Long userId, LocalDate targetDate, LocalDate today) {
        return today.equals(targetDate)
                ? Math.min(REWARD_AMOUNT, dailyRewardService.remainingReward(userId, today))
                : 0;
    }

    // 호출자가 이미 잠근 코인 지갑과 저장한 완료 로그 ID를 사용함.
    public void grantRoutineReward(UserWallet wallet, int amount, Long routineLogId) {
        grant(wallet, amount, WalletHistoryReason.ROUTINE_COMPLETE,
                WalletHistory.SOURCE_ROUTINE_LOG, routineLogId);
    }

    // 투두 완료 상태를 반영한 뒤, 성장 지급액 기록과 방 성장 반영 전에 호출함.
    public void grantTodoReward(UserWallet wallet, int amount, Long todoId) {
        grant(wallet, amount, WalletHistoryReason.TODO_COMPLETE,
                WalletHistory.SOURCE_TODO, todoId);
    }

    private void grant(UserWallet wallet, int amount, WalletHistoryReason reason,
                       String sourceType, Long sourceId) {
        if (amount <= 0) {
            return;
        }
        wallet.add(amount);
        walletHistoryRecorder.record(wallet, amount, reason, sourceType, sourceId);
    }
}
