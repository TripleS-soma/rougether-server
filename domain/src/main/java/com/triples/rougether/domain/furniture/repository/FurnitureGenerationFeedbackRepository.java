package com.triples.rougether.domain.furniture.repository;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationFeedback;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FurnitureGenerationFeedbackRepository extends JpaRepository<FurnitureGenerationFeedback, Long> {
    Optional<FurnitureGenerationFeedback> findByJobIdAndRequestId(String jobId, String requestId);
    void deleteByJobId(String jobId);
}
