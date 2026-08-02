package com.triples.rougether.domain.house.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

// 미션 일별 기여 이력 1건 (#201). DAILY·WEEKLY 공통 기록.
// UNIQUE(mission, membership, contribution_date) 가 하루(KST) 1회 기여의 DB 방어선.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "house_mission_daily_contributions")
@EntityListeners(AuditingEntityListener.class)
public class HouseMissionDailyContribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    private HouseMission mission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private HouseMember member;

    @Column(name = "contribution_date", nullable = false)
    private LocalDate contributionDate;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static HouseMissionDailyContribution record(HouseMission mission, HouseMember member,
                                                       LocalDate contributionDate) {
        HouseMissionDailyContribution contribution = new HouseMissionDailyContribution();
        contribution.mission = mission;
        contribution.member = member;
        contribution.contributionDate = contributionDate;
        return contribution;
    }
}
