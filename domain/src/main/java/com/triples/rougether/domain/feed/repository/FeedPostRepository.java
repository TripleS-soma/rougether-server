package com.triples.rougether.domain.feed.repository;

import com.triples.rougether.domain.feed.entity.FeedPost;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface FeedPostRepository extends JpaRepository<FeedPost, Long> {
    @Query("select p.author.id from FeedPost p where p.id = :id")
    Optional<Long> findAuthorId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from FeedPost p where p.id = :id")
    Optional<FeedPost> findForUpdate(@Param("id") Long id);
    Optional<FeedPost> findByAuthorIdAndClientPostId(Long authorId, String clientPostId);

    @Query("select p from FeedPost p join fetch p.author a where p.deletedAt is null and a.deletedAt is null "
            + "and (:authorId is null or a.id = :authorId) and (:before is null or p.id < :before) order by p.id desc")
    List<FeedPost> findPage(@Param("authorId") Long authorId, @Param("before") Long before, Pageable page);
    @Query("select p from FeedPost p join fetch p.author a where p.id = :id and p.deletedAt is null and a.deletedAt is null")
    Optional<FeedPost> findVisible(@Param("id") Long id);

    @Modifying
    @Query("update FeedPost p set p.content = '', p.deletedAt = :now where p.deletedAt is null and p.author.deletedAt is not null")
    int eraseWithdrawn(@Param("now") java.time.Instant now);
}
