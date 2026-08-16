package com.triples.rougether.domain.attendance.entity;

import com.triples.rougether.domain.shop.entity.Item;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "daily_coin_amount", nullable = false)
    private int dailyCoinAmount;

    @Column(name = "bonus_day", nullable = false)
    private int bonusDay;

    @Column(name = "bonus_coin_amount", nullable = false)
    private int bonusCoinAmount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reward_item_id", nullable = false)
    private Item rewardItem;

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

    public int coinRewardFor(int streakDay) {
        return streakDay == bonusDay ? bonusCoinAmount : dailyCoinAmount;
    }
}
