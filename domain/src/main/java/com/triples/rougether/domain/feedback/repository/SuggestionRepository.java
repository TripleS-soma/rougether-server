package com.triples.rougether.domain.feedback.repository;

import com.triples.rougether.domain.feedback.entity.Suggestion;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface SuggestionRepository extends JpaRepository<Suggestion, Long> {
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from Suggestion s where s.user.id = :userId")
    void deleteAllByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
    @EntityGraph(attributePaths = "user")
    List<Suggestion> findByIdLessThanOrderByIdDesc(Long cursor, Pageable pageable);
    List<Suggestion> findByUserIdAndIdLessThanOrderByIdDesc(Long userId, Long cursor, Pageable pageable);
}
