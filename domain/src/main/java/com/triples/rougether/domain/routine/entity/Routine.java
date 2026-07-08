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

    @Column(name = "origin_routine_id")
    private Long originRoutineId;

    private Routine(User user, Category category, String title, AuthType authType,
                    String repeatType, String repeatDays, LocalTime scheduledTime,
                    LocalDate startsOn, LocalDate endsOn) {
        this.user = user;
        this.category = category;
        this.title = title;
        this.authType = authType;
        this.status = RoutineStatus.ACTIVE;
        this.repeatType = repeatType;
        this.repeatDays = repeatDays;
        this.scheduledTime = scheduledTime;
        this.startsOn = startsOn;
        this.endsOn = endsOn;
    }

    public static Routine create(User user, Category category, String title, AuthType authType,
                                 String repeatType, String repeatDays, LocalTime scheduledTime,
                                 LocalDate startsOn, LocalDate endsOn) {
        return new Routine(user, category, title, authType, repeatType, repeatDays,
                scheduledTime, startsOn, endsOn);
    }

    // scheduledTime·endsOn은 해제(null) 반영을 위해 호출자가 유효값을 확정해 넘기며 무조건 대입함.
    // 나머지 필드는 null이면 기존 값 유지(부분수정)
    public void update(String title, AuthType authType, String repeatType, String repeatDays,
                       LocalTime scheduledTime, LocalDate startsOn, LocalDate endsOn) {
        // title은 NOT NULL 업무필수라 공백이면 덮어쓰지 않음
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        if (authType != null) {
            this.authType = authType;
        }
        if (repeatType != null) {
            this.repeatType = repeatType;
        }
        if (repeatDays != null) {
            this.repeatDays = repeatDays;
        }
        this.scheduledTime = scheduledTime;
        if (startsOn != null) {
            this.startsOn = startsOn;
        }
        this.endsOn = endsOn;
    }

    public void changeCategory(Category category) {
        this.category = category;
    }

    // 생성 저장 직후 origin을 자기 id로 지정함(계보 루트)
    public void assignOriginToSelf() {
        this.originRoutineId = this.id;
    }

    // 버전 분기용 복제. 인자가 null이면 이 버전 값을 유지(update와 같은 병합 규칙),
    // 단 scheduledTime·endsOn은 호출자가 확정한 유효값(해제 시 null 포함)을 그대로 씀.
    // status·origin은 이 버전에서 승계. created_at은 auditing이 now로 채움
    public Routine copyAsNewVersion(Category category, String title, AuthType authType,
                                    String repeatType, String repeatDays, LocalTime scheduledTime,
                                    LocalDate startsOn, LocalDate endsOn) {
        Routine copy = new Routine(this.user,
                category != null ? category : this.category,
                title != null && !title.isBlank() ? title : this.title,
                authType != null ? authType : this.authType,
                repeatType != null ? repeatType : this.repeatType,
                repeatDays != null ? repeatDays : this.repeatDays,
                scheduledTime,
                startsOn != null ? startsOn : this.startsOn,
                endsOn);
        copy.status = this.status;
        copy.originRoutineId = this.originRoutineId;
        return copy;
    }

    public void softDelete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
