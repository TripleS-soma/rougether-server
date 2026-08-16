package com.triples.rougether.domain.attendance.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.shop.entity.UserItem;
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
@Table(name = "attendance_check_ins")
public class AttendanceCheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private AttendanceEvent event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "streak_day", nullable = false)
    private int streakDay;

    @Column(name = "coin_reward_amount", nullable = false)
    private int coinRewardAmount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_user_item_id")
    private UserItem rewardUserItem;

    @Column(name = "reward_newly_granted")
    private Boolean rewardNewlyGranted;

    @Column(name = "reward_processed_at")
    private Instant rewardProcessedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private AttendanceCheckIn(AttendanceEvent event, User user, LocalDate attendanceDate,
                              int streakDay, int coinRewardAmount, Instant createdAt) {
        this.event = event;
        this.user = user;
        this.attendanceDate = attendanceDate;
        this.streakDay = streakDay;
        this.coinRewardAmount = coinRewardAmount;
        this.createdAt = createdAt;
    }

    public static AttendanceCheckIn record(AttendanceEvent event, User user, LocalDate attendanceDate,
                                           int streakDay, int coinRewardAmount, Instant createdAt) {
        return new AttendanceCheckIn(event, user, attendanceDate, streakDay, coinRewardAmount, createdAt);
    }

    public void processReward(UserItem userItem, boolean newlyGranted, Instant processedAt) {
        this.rewardUserItem = userItem;
        this.rewardNewlyGranted = newlyGranted;
        this.rewardProcessedAt = processedAt;
    }

    public boolean isCompleted() {
        return rewardProcessedAt != null;
    }
}
