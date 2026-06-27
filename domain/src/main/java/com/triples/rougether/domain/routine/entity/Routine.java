package com.triples.rougether.domain.routine.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseEntity;
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
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "routines")
public class Routine extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "title", length = 160, nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", length = 30, nullable = false)
    private AuthType authType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private RoutineStatus status;

    @Column(name = "repeat_type", length = 40)
    private String repeatType;

    // 매일/매주(요일)/주 N회 설정. spec상 JSON 배열 형태로 저장함.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "repeat_days")
    private String repeatDays;

    @Column(name = "scheduled_time")
    private LocalTime scheduledTime;

    @Column(name = "starts_on")
    private LocalDate startsOn;

    @Column(name = "ends_on")
    private LocalDate endsOn;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
