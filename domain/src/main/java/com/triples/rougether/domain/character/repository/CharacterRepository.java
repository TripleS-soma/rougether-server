package com.triples.rougether.domain.character.repository;

import com.triples.rougether.domain.character.entity.Character;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CharacterRepository extends JpaRepository<Character, Long> {

    Optional<Character> findByCode(String code);

    List<Character> findByActiveTrueOrderBySortOrderAscIdAsc();

    List<Character> findAllByOrderBySortOrderAscIdAsc();
}
