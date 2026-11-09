package com.triples.rougether.userapi.routine.reward.service;

import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final ChristmasFurnitureBonusService christmasFurnitureBonusService;
    private final Clock clock;

    // 잠금 뒤 한 번 캡처한 시각에서 오늘과 completedAt을 함께 만들어 자정 경합을 피함.
    public CompletionTime completionTime() {
        Instant now = clock.instant();
        return new CompletionTime(now, LocalDate.ofInstant(now, ZoneId.of("Asia/Seoul")));
    }

    // 사용자·코인 지갑 잠금 뒤, 이번 완료가 합계에 포함되기 전에 계산함.
    public CompletionReward calculateReward(Long userId, LocalDate targetDate, LocalDate today) {
        int baseAmount = today.equals(targetDate)
                ? Math.min(REWARD_AMOUNT, dailyRewardService.remainingReward(userId, today)) : 0;
        int bonus = christmasFurnitureBonusService.bonusCoins(userId, today, baseAmount);
        return new CompletionReward(baseAmount, Math.addExact(baseAmount, bonus));
    }

    public record CompletionReward(int baseAmount, int totalAmount) {
    }

    public record CompletionTime(Instant completedAt, LocalDate today) {
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
