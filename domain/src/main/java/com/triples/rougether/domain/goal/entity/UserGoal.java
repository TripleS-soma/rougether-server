package com.triples.rougether.domain.goal.entity;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_goals")
public class UserGoal extends BaseCreatedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goal_id", nullable = false)
    private Goal goal;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    private UserGoal(User user, Goal goal, boolean primary) {
        this.user = user;
        this.goal = goal;
        this.primary = primary;
    }

    public static UserGoal of(User user, Goal goal, boolean primary) {
        return new UserGoal(user, goal, primary);
    }

    public void changePrimary(boolean primary) {
        this.primary = primary;
    }
}
