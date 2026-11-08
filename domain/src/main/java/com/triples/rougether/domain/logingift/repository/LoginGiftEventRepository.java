package com.triples.rougether.domain.logingift.repository;

import com.triples.rougether.domain.logingift.entity.LoginGiftEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginGiftEventRepository extends JpaRepository<LoginGiftEvent, Long> {
    List<LoginGiftEvent> findByEnabledTrue();
    Optional<LoginGiftEvent> findByCode(String code);
}
