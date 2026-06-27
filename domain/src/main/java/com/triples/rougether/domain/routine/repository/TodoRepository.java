package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Todo;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findByUserIdAndStatusAndDeletedAtIsNull(Long userId, String status);
}
