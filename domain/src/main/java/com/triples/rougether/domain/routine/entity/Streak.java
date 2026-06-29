package com.triples.rougether.domain.routine.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "streaks")
@EntityListeners(AuditingEntityListener.class)
public class Streak {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "current_count", nullable = false)
    private int currentCount;

    @Column(name = "longest_count", nullable = false)
    private int longestCount;

    @Column(name = "last_success_date")
    private LocalDate lastSuccessDate;

    @Column(name = "last_evaluated_date")
    private LocalDate lastEvaluatedDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private StreakStatus status;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Streak(User user, LocalDate today) {
        this.user = user;
        this.currentCount = 1;
        this.longestCount = 1;
        this.lastSuccessDate = today;
        this.status = StreakStatus.ACTIVE;
    }

    // 오늘 첫 완료로 스트릭을 시작함(streak row 없을 때)
    public static Streak start(User user, LocalDate today) {
        return new Streak(user, today);
    }

    // 오늘 첫 완료 시 호출. 어제 성공이면 +1, 아니면 1로 리셋. 이미 오늘 반영됐으면 변화 없음(방어).
    public void applySuccess(LocalDate today) {
        if (today.equals(lastSuccessDate)) {
            return;
        }
        if (today.minusDays(1).equals(lastSuccessDate)) {
            currentCount += 1;
        } else {
            currentCount = 1;
        }
        longestCount = Math.max(longestCount, currentCount);
        lastSuccessDate = today;
        status = StreakStatus.ACTIVE;
    }

    // 오늘 완료가 0개가 됐을 때 호출. count-1, last_success_date는 어제로 근사 복원(0이면 null).
    // longest_count는 보수적으로 줄이지 않음.
    public void rollback(LocalDate today) {
        currentCount = Math.max(0, currentCount - 1);
        if (currentCount > 0) {
            lastSuccessDate = today.minusDays(1);
            status = StreakStatus.ACTIVE;
        } else {
            lastSuccessDate = null;
            status = StreakStatus.BROKEN;
        }
    }
}
