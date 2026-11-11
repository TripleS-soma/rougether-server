package com.triples.rougether.domain.feedback.repository;

import com.triples.rougether.domain.feedback.entity.AppUpdate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUpdateRepository extends JpaRepository<AppUpdate, Long> {
    List<AppUpdate> findByIdLessThanOrderByIdDesc(Long cursor, Pageable pageable);
}
