package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.Character;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRepository extends JpaRepository<Character, Long> {
}
