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

    private RoutineLog(Routine routine, LocalDate routineDate, RoutineLogStatus status,
                       Instant completedAt, CurrencyType rewardCurrencyType, int rewardAmount) {
        this.routine = routine;
        this.routineDate = routineDate;
        this.status = status;
        this.completedAt = completedAt;
        this.rewardCurrencyType = rewardCurrencyType;
        this.rewardAmount = rewardAmount;
    }

    public static RoutineLog complete(Routine routine, LocalDate routineDate, Instant completedAt,
                                      CurrencyType rewardCurrencyType, int rewardAmount) {
        return new RoutineLog(routine, routineDate, RoutineLogStatus.COMPLETED,
                completedAt, rewardCurrencyType, rewardAmount);
    }

    public static RoutineLog fail(Routine routine, LocalDate routineDate) {
        return new RoutineLog(routine, routineDate, RoutineLogStatus.FAILED, null, null, 0);
    }

    public void revertToFailed() {
        if (this.status != RoutineLogStatus.COMPLETED) {
            throw new IllegalStateException("COMPLETED 상태의 로그만 FAILED로 복원할 수 있음: " + this.status);
        }
        this.status = RoutineLogStatus.FAILED;
        this.completedAt = null;
        this.rewardCurrencyType = null;
        this.rewardAmount = 0;
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
}
