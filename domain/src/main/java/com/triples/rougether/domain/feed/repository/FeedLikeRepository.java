package com.triples.rougether.domain.feed.repository;

import com.triples.rougether.domain.feed.entity.FeedLike;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface FeedLikeRepository extends JpaRepository<FeedLike, Long> {
    Optional<FeedLike> findByPostIdAndUserId(Long postId, Long userId);
    @Query("select l.post.id as postId, count(l) as total from FeedLike l where l.post.id in :ids "
            + "and l.user.deletedAt is null group by l.post.id")
    List<FeedCount> countForPosts(@Param("ids") List<Long> ids);
    @Query("select l.post.id from FeedLike l where l.user.id = :userId and l.post.id in :ids")
    List<Long> findLikedPostIds(@Param("userId") Long userId, @Param("ids") List<Long> ids);
    @Modifying @Query("delete from FeedLike l where l.post.id = :postId")
    void deleteForPost(@Param("postId") Long postId);
    @Modifying @Query("delete from FeedLike l where l.user.deletedAt is not null or l.post.deletedAt is not null")
    void deleteWithdrawn();
}
