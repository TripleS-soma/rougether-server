package com.triples.rougether.domain.gacha.repository;

import com.triples.rougether.domain.gacha.entity.Gacha;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GachaRepository extends JpaRepository<Gacha, Long> {

    List<Gacha> findByThemeIdAndActiveIsTrue(Long themeId);
}
