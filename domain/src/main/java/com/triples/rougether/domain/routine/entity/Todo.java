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

    private Todo(User user, Category category, String title, String description, LocalDate dueDate) {
        this.user = user;
        this.category = category;
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.status = TodoStatus.PENDING;
        this.rewardAmount = 0;
    }

    public static Todo create(User user, Category category, String title, String description,
                              LocalDate dueDate) {
        return new Todo(user, category, title, description, dueDate);
    }

    public void update(String title, String description, LocalDate dueDate) {
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
