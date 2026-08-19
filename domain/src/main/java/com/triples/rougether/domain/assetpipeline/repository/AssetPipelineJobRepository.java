package com.triples.rougether.domain.assetpipeline.repository;

import com.triples.rougether.domain.assetpipeline.entity.AssetPipelineJob;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetPipelineJobRepository extends JpaRepository<AssetPipelineJob, Long> {

    boolean existsBySlug(String slug);

    List<AssetPipelineJob> findAllByOrderByUpdatedAtDesc();
}
