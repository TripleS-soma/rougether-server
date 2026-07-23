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

// DAILY 미션의 일별 보상 지급 이력 1건 (#201). UNIQUE(mission, reward_date) 가 하루 1회 claim 의 DB 방어선.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "house_mission_daily_rewards")
@EntityListeners(AuditingEntityListener.class)
public class HouseMissionDailyReward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mission_id", nullable = false)
    private HouseMission mission;

    @Column(name = "reward_date", nullable = false)
    private LocalDate rewardDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claimed_membership_id", nullable = false)
    private HouseMember claimedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static HouseMissionDailyReward claim(HouseMission mission, LocalDate rewardDate, HouseMember claimedBy) {
        HouseMissionDailyReward reward = new HouseMissionDailyReward();
        reward.mission = mission;
        reward.rewardDate = rewardDate;
        reward.claimedBy = claimedBy;
        return reward;
    }
}
