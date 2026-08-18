package com.triples.rougether.domain.routine.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.shared.CurrencyType;
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

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "todos")
public class Todo extends BaseEntity {

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

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "due_time")
    private LocalTime dueTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private TodoStatus status;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_currency_type", length = 30)
    private CurrencyType rewardCurrencyType;

    @Column(name = "reward_amount", nullable = false)
    private int rewardAmount;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    // 기기 캘린더 임포트 출처(예 GOOGLE_CALENDAR)와 그 캘린더의 이벤트 id. 일반 투두는 둘 다 null.
    // (user_id, external_source, external_id) unique 가 중복 임포트의 방어선이며 생성 후 바뀌지 않는다
    @Column(name = "external_source", length = 30)
    private String externalSource;

    @Column(name = "external_id", length = 255)
    private String externalId;

    private Todo(User user, Category category, String title, String description, LocalDate dueDate,
                LocalTime dueTime, String externalSource, String externalId) {
        this.user = user;
        this.category = category;
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.dueTime = dueTime == null ? null : dueTime.withSecond(0).withNano(0);
        this.status = TodoStatus.PENDING;
        this.rewardAmount = 0;
        this.externalSource = externalSource;
        this.externalId = externalId;
    }

    public static Todo create(User user, Category category, String title, String description,
                              LocalDate dueDate, LocalTime dueTime) {
        return new Todo(user, category, title, description, dueDate, dueTime, null, null);
    }

    // 기기 캘린더 임포트: 외부 참조를 함께 기록. 두 값은 호출자가 둘 다 채워서 넘긴다
    public static Todo createImported(User user, Category category, String title, String description,
                                      LocalDate dueDate, LocalTime dueTime,
                                      String externalSource, String externalId) {
        return new Todo(user, category, title, description, dueDate, dueTime, externalSource, externalId);
    }

    // dueTime은 해제(null) 반영을 위해 무조건 대입함 — 루틴 scheduledTime과 동일 정책
    public void update(String title, String description, LocalDate dueDate, LocalTime dueTime) {
        // title은 NOT NULL 업무필수라 공백이면 덮어쓰지 않음
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
        if (description != null) {
            this.description = description;
        }
        if (dueDate != null) {
            this.dueDate = dueDate;
        }
        this.dueTime = dueTime == null ? null : dueTime.withSecond(0).withNano(0);
    }

    public void changeCategory(Category category) {
        this.category = category;
    }

    public void complete(CurrencyType rewardCurrencyType, int rewardAmount, Instant completedAt) {
        this.status = TodoStatus.COMPLETED;
        this.completedAt = completedAt;
        this.rewardCurrencyType = rewardCurrencyType;
        this.rewardAmount = rewardAmount;
    }

    public void cancelComplete() {
        this.status = TodoStatus.PENDING;
        this.completedAt = null;
        this.rewardCurrencyType = null;
        this.rewardAmount = 0;
    }

    public void softDelete(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
