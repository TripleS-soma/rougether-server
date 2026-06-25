package com.triples.rougether.domain.gacha.repository;

import com.triples.rougether.domain.gacha.entity.Gacha;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GachaRepository extends JpaRepository<Gacha, Long> {
}
