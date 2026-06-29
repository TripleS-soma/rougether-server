package com.triples.rougether.domain.shop.repository;

import com.triples.rougether.domain.shop.entity.Theme;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ThemeRepository extends JpaRepository<Theme, Long> {

    Optional<Theme> findByCode(String code);
}
