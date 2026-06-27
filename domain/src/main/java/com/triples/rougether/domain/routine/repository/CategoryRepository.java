package com.triples.rougether.domain.routine.repository;

import com.triples.rougether.domain.routine.entity.Category;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByUserIdAndDeletedAtIsNull(Long userId);
}
