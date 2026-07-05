package com.triples.rougether.domain.house.entity;

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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "house_missions")
public class HouseMission extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "house_id", nullable = false)
    private House house;

    @Column(name = "title", length = 160, nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "mission_type", length = 50, nullable = false)
    private HouseMissionType missionType;

    @Column(name = "target_value", nullable = false)
    private int targetValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private HouseMissionStatus status;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    // 미션 등록 - ACTIVE 로 시작. 달성 판정(currentValue)은 participants 합산으로 서비스가 계산한다.
    public static HouseMission create(House house, String title, HouseMissionType missionType,
                                      int targetValue, Instant startsAt, Instant endsAt) {
        HouseMission mission = new HouseMission();
        mission.house = house;
        mission.title = title;
        mission.missionType = missionType;
        mission.targetValue = targetValue;
        mission.status = HouseMissionStatus.ACTIVE;
        mission.startsAt = startsAt;
        mission.endsAt = endsAt;
        return mission;
    }

    // 달성 확정 - claim 에서 성장 포인트 지급과 한 트랜잭션(행 락)으로 전환한다.
    public void complete() {
        this.status = HouseMissionStatus.COMPLETED;
    }

    public boolean isActive() {
        return status == HouseMissionStatus.ACTIVE;
    }

    // 기간 밖(시작 전/종료 후)이면 기여 불가. 기간 미지정은 항상 기여 가능.
    public boolean isWithinPeriod(Instant now) {
        if (startsAt != null && now.isBefore(startsAt)) {
            return false;
        }
        return endsAt == null || !now.isAfter(endsAt);
    }
}
