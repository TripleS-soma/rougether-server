package com.triples.rougether.domain.recommendation.entity;

import com.triples.rougether.domain.member.entity.User;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// AI 조정 추천(#329). 주간 배치가 실패 패턴 룰로 만든 반복 스케줄 조정 제안 1건 — 적용은 사용자 수락으로만.
// originRoutineId 는 대상 루틴 계보 루트, routineId 는 생성 시점의 대상 버전(수락 시 계보 현재 버전과 다르면 stale 거부),
// appliedRoutineId 는 수락 적용으로 분기된 새 버전(효과 측정 조인 키). proposal 은 제안 스케줄 절대값 JSON.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "routine_recommendations")
public class RoutineRecommendation extends BaseCreatedEntity {

    public static final int MESSAGE_MAX_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "origin_routine_id", nullable = false)
    private Long originRoutineId;

    @Column(name = "routine_id", nullable = false)
    private Long routineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rec_type", length = 30, nullable = false)
    private RecommendationType recType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 30, nullable = false)
    private RecommendationSource source;

    // {"repeatType":"WEEKLY","daysOfWeek":["MON",...]} — 루틴 repeat_days 와 같은 요일 토큰
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposal", nullable = false)
    private String proposalJson;

    @Column(name = "message", length = MESSAGE_MAX_LENGTH, nullable = false)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private RecommendationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "acted_at")
    private Instant actedAt;

    @Column(name = "applied_routine_id")
    private Long appliedRoutineId;

    private RoutineRecommendation(User user, Long originRoutineId, Long routineId, RecommendationType recType,
                                  RecommendationSource source, String proposalJson, String message,
                                  Instant expiresAt) {
        this.user = user;
        this.originRoutineId = originRoutineId;
        this.routineId = routineId;
        this.recType = recType;
        this.source = source;
        this.proposalJson = proposalJson;
        this.message = message;
        this.status = RecommendationStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }

    public static RoutineRecommendation rule(User user, Long originRoutineId, Long routineId,
                                             RecommendationType recType, String proposalJson, String message,
                                             Instant expiresAt) {
        return new RoutineRecommendation(user, originRoutineId, routineId, recType, RecommendationSource.RULE,
                proposalJson, message, expiresAt);
    }

    // 만료는 상태 전이 배치 없이 조회·수락 시점에 이 판정으로 결정함(경과 = 만료)
    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void accept(Instant actedAt, Long appliedRoutineId) {
        this.status = RecommendationStatus.ACCEPTED;
        this.actedAt = actedAt;
        this.appliedRoutineId = appliedRoutineId;
    }

    public void dismiss(Instant actedAt) {
        this.status = RecommendationStatus.DISMISSED;
        this.actedAt = actedAt;
    }
}
