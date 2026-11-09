package com.triples.rougether.domain.routine.entity;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "routine_logs")
public class RoutineLog extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "routine_id", nullable = false)
    private Routine routine;

    @Column(name = "routine_date", nullable = false)
    private LocalDate routineDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private RoutineLogStatus status;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_currency_type", length = 30)
    private CurrencyType rewardCurrencyType;

    @Column(name = "reward_amount", nullable = false)
    private int rewardAmount;

    @Column(name = "base_reward_amount")
    private Integer baseRewardAmount;

    @Column(name = "growth_reward_amount", nullable = false)
    private int growthRewardAmount;

    private RoutineLog(Routine routine, LocalDate routineDate, RoutineLogStatus status,
                       Instant completedAt, CurrencyType rewardCurrencyType, int rewardAmount, int baseRewardAmount) {
        validateReward(rewardAmount, baseRewardAmount);
        this.routine = routine;
        this.routineDate = routineDate;
        this.status = status;
        this.completedAt = completedAt;
        this.rewardCurrencyType = rewardCurrencyType;
        this.rewardAmount = rewardAmount;
        // 보너스가 없으면 NULL로 두어 구버전 writer도 실지급액을 원금으로 갱신할 수 있게 함.
        this.baseRewardAmount = baseRewardAmount == rewardAmount ? null : baseRewardAmount;
    }

    public static RoutineLog complete(Routine routine, LocalDate routineDate, Instant completedAt,
                                      CurrencyType rewardCurrencyType, int rewardAmount) {
        return new RoutineLog(routine, routineDate, RoutineLogStatus.COMPLETED,
                completedAt, rewardCurrencyType, rewardAmount, rewardAmount);
    }

    public static RoutineLog complete(Routine routine, LocalDate routineDate, Instant completedAt,
                                      CurrencyType rewardCurrencyType, int rewardAmount, int baseRewardAmount) {
        return new RoutineLog(routine, routineDate, RoutineLogStatus.COMPLETED,
                completedAt, rewardCurrencyType, rewardAmount, baseRewardAmount);
    }

    public static RoutineLog fail(Routine routine, LocalDate routineDate) {
        return new RoutineLog(routine, routineDate, RoutineLogStatus.FAILED, null, null, 0, 0);
    }

    // 발생분 건너뜀(mobile #189) — 완료도 실패도 아님. unique(routine_id, routine_date)를 점유해
    // day-end 배치가 그날 FAILED 를 만들지 않게 하고, today/calendar 는 이 날짜의 발생분을 숨긴다.
    public static RoutineLog skip(Routine routine, LocalDate routineDate) {
        return new RoutineLog(routine, routineDate, RoutineLogStatus.SKIPPED, null, null, 0, 0);
    }

    public void recordGrowthReward(int amount) {
        if (status != RoutineLogStatus.COMPLETED || amount < 0 || amount > getBaseRewardAmount()
                || growthRewardAmount != 0) {
            throw new IllegalStateException("완료 보상 범위에서 성장 포인트를 한 번만 기록할 수 있음");
        }
        this.growthRewardAmount = amount;
    }

    public void revertToFailed() {
        if (this.status != RoutineLogStatus.COMPLETED) {
            throw new IllegalStateException("COMPLETED 상태의 로그만 FAILED로 복원할 수 있음: " + this.status);
        }
        this.status = RoutineLogStatus.FAILED;
        this.completedAt = null;
        this.rewardCurrencyType = null;
        this.rewardAmount = 0;
        this.baseRewardAmount = null;
        this.growthRewardAmount = 0;
    }

    public void completeFromFailed(Instant completedAt, CurrencyType rewardCurrencyType) {
        if (this.status != RoutineLogStatus.FAILED) {
            throw new IllegalStateException("FAILED 상태의 로그만 완료로 전이할 수 있음: " + this.status);
        }
        this.status = RoutineLogStatus.COMPLETED;
        this.completedAt = completedAt;
        // fail()이 통화를 null로 두므로 여기서 채워야 일반 완료 경로와 응답 계약이 같아짐(금액은 0 유지)
        this.rewardCurrencyType = rewardCurrencyType;
    }

    private static void validateReward(int totalAmount, int baseAmount) {
        if (baseAmount < 0 || totalAmount < baseAmount) {
            throw new IllegalArgumentException("실제 코인 지급액은 음수가 아닌 원금 이상이어야 함");
        }
    }

    // 혼합 배포 중 구버전이 저장한 NULL 원금은 기존 총 지급액과 동일하게 해석함.
    public int getBaseRewardAmount() {
        return baseRewardAmount != null ? baseRewardAmount : rewardAmount;
    }
}
