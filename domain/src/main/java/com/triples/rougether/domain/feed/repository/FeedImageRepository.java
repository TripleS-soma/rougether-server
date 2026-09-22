package com.triples.rougether.domain.feed.repository;

import com.triples.rougether.domain.feed.entity.FeedImage;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface FeedImageRepository extends JpaRepository<FeedImage, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from FeedImage i where i.id = :id")
    Optional<FeedImage> findForUpdate(@Param("id") Long id);
    List<FeedImage> findByPostIdInOrderByPostIdAscSortOrderAsc(List<Long> postIds);
    long countByOwnerIdAndPostIsNull(Long ownerId);

    @Query("select i.id from FeedImage i left join i.post p where i.id > :after and "
            + "((i.post is null and i.expiresAt <= :now) or (i.ready = true and "
            + "(i.owner.deletedAt is not null or p.deletedAt is not null))) order by i.id")
    List<Long> findCleanupCandidates(@Param("after") long after, @Param("now") Instant now, Pageable page);
}
