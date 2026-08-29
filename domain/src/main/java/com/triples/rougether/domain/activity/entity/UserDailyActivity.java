package com.triples.rougether.domain.activity.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 인증된 실사용자의 일별 최초 user-api 활동. 사용자·KST 날짜당 한 건만 저장함.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_daily_activity",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_daily_activity_user_date",
                columnNames = {"user_id", "activity_date"}))
public class UserDailyActivity extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;
}
