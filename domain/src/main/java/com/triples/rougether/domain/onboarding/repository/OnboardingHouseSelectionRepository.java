package com.triples.rougether.domain.onboarding.repository;

import com.triples.rougether.domain.onboarding.entity.OnboardingHouseSelection;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OnboardingHouseSelectionRepository extends JpaRepository<OnboardingHouseSelection, Long> {
    @Override
    @EntityGraph(attributePaths = {"membership", "membership.house"})
    Optional<OnboardingHouseSelection> findById(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from OnboardingHouseSelection s where s.userId = :userId")
    Optional<OnboardingHouseSelection> findWithLockByUserId(@Param("userId") Long userId);
}
