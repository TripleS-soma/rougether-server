package com.triples.rougether.domain.attendance.entity;

import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.character.entity.Character;
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
@Table(name = "attendance_events")
public class AttendanceEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", length = 50, nullable = false, unique = true)
    private String code;

    @Column(name = "title", length = 120, nullable = false)
    private String title;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(name = "target_days", nullable = false)
    private int targetDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttendanceMode mode = AttendanceMode.STREAK;

    @Column(name = "milestone_every", nullable = false)
    private int milestoneEvery;

    @Column(name = "milestone_coin_amount", nullable = false)
    private int milestoneCoinAmount;

    @Column(name = "daily_coin_amount", nullable = false)
    private int dailyCoinAmount;

    @Column(name = "bonus_day", nullable = false)
    private int bonusDay;

    @Column(name = "bonus_coin_amount", nullable = false)
    private int bonusCoinAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_item_id")
    private Item rewardItem;

    @Column(nullable = false)
    private int generationCreditAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_character_id")
    private Character rewardCharacter;

    @Column(name = "character_reward_day", nullable = false)
    private int characterRewardDay;


    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private AttendanceEvent(String code, String title, LocalDate startsOn, LocalDate endsOn,
                            int targetDays, int dailyCoinAmount, int bonusDay,
                            int bonusCoinAmount, Item rewardItem) {
        this.code = code;
        this.title = title;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
        this.targetDays = targetDays;
        this.dailyCoinAmount = dailyCoinAmount;
        this.bonusDay = bonusDay;
        this.bonusCoinAmount = bonusCoinAmount;
        this.rewardItem = rewardItem;
        this.active = true;
        this.createdAt = Instant.now();
    }

    public static AttendanceEvent create(String code, String title, LocalDate startsOn, LocalDate endsOn,
                                         int targetDays, int dailyCoinAmount, int bonusDay,
                                         int bonusCoinAmount, Item rewardItem) {
        return new AttendanceEvent(code, title, startsOn, endsOn, targetDays,
                dailyCoinAmount, bonusDay, bonusCoinAmount, rewardItem);
    }

    public static AttendanceEvent createGenerationEvent(String code, String title, LocalDate startsOn,
            LocalDate endsOn, int dailyCoinAmount, int bonusDay, int bonusCoinAmount) {
        AttendanceEvent event = create(code, title, startsOn, endsOn, 7,
                dailyCoinAmount, bonusDay, bonusCoinAmount, null);
        event.generationCreditAmount = 1;
        return event;
    }

    public static AttendanceEvent createCumulativeCoins(String code, String title, LocalDate startsOn,
            LocalDate endsOn, int targetDays, int dailyCoinAmount, int milestoneEvery,
            int milestoneCoinAmount) {
        if (targetDays < 2 || targetDays > 365 || dailyCoinAmount < 0 || milestoneEvery < 1
                || milestoneEvery > targetDays || milestoneCoinAmount < 0
                || endsOn.isBefore(startsOn.plusDays(targetDays - 1L))) {
            throw new IllegalArgumentException("누적 출석 보상과 기간 설정이 올바르지 않습니다.");
        }
        AttendanceEvent event = create(code, title, startsOn, endsOn, targetDays,
                dailyCoinAmount, targetDays, dailyCoinAmount, null);
        event.mode = AttendanceMode.CUMULATIVE;
        event.milestoneEvery = milestoneEvery;
        event.milestoneCoinAmount = milestoneCoinAmount;
        return event;
    }

    public void configureCharacterReward(Character character, int day) {
        if (mode != AttendanceMode.CUMULATIVE || character == null || !character.isAttendanceReward()
                || day < 1 || day > targetDays) {
            throw new IllegalArgumentException("누적 출석 캐릭터 보상 설정이 올바르지 않습니다.");
        }
        this.rewardCharacter = character;
        this.characterRewardDay = day;
    }

    public boolean isActiveOn(LocalDate date) {
        return active && !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    public int coinRewardFor(int streakDay) {
        if (mode == AttendanceMode.CUMULATIVE && milestoneEvery > 0 && streakDay % milestoneEvery == 0) {
            return milestoneCoinAmount;
        }
        return streakDay == bonusDay ? bonusCoinAmount : dailyCoinAmount;
    }
}
