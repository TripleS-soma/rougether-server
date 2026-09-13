package com.triples.rougether.domain.onboarding.entity;

import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
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
@Table(name = "onboarding_house_selections")
public class OnboardingHouseSelection extends BaseEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "choice", nullable = false, length = 20)
    private OnboardingHouseChoice choice;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private OnboardingHouseResult result;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "membership_id", nullable = false)
    private HouseMember membership;

    public static OnboardingHouseSelection create(Long userId, OnboardingHouseChoice choice,
                                                  OnboardingHouseResult result, HouseMember membership) {
        OnboardingHouseSelection selection = new OnboardingHouseSelection();
        selection.userId = userId;
        selection.choice = choice;
        selection.result = result;
        selection.membership = membership;
        return selection;
    }
}
