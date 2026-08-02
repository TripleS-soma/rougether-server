package com.triples.rougether.domain.moderation.repository;

import com.triples.rougether.domain.moderation.entity.BannedWord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BannedWordRepository extends JpaRepository<BannedWord, Long> {

    boolean existsByWord(String word);

    List<BannedWord> findAllByOrderByWordAsc();
}
