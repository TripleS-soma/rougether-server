package com.triples.rougether.domain.feed.repository;

import com.triples.rougether.domain.feed.entity.FeedComment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface FeedCommentRepository extends JpaRepository<FeedComment, Long> {
    Optional<FeedComment> findByPostIdAndAuthorIdAndClientCommentId(Long postId, Long authorId, String clientCommentId);
    Optional<FeedComment> findByIdAndPostId(Long id, Long postId);
    // 요청자가 차단한 작성자의 댓글은 목록·개수에서 뺌(#399). 개수도 요청자 기준이라 목록과 어긋나지 않음.
    @Query("select c from FeedComment c join fetch c.author a where c.post.id = :postId "
            + "and c.deletedAt is null and a.deletedAt is null and (:after is null or c.id > :after) "
            + "and not exists (select b.id from UserBlock b where b.blockerUserId = :viewer and b.blockedUserId = a.id) "
            + "order by c.id")
    List<FeedComment> findPage(@Param("viewer") Long viewer, @Param("postId") Long postId,
                               @Param("after") Long after, Pageable page);
    @Query("select c.post.id as postId, count(c) as total from FeedComment c where c.post.id in :ids "
            + "and c.deletedAt is null and c.author.deletedAt is null "
            + "and not exists (select b.id from UserBlock b where b.blockerUserId = :viewer "
            + "and b.blockedUserId = c.author.id) group by c.post.id")
    List<FeedCount> countForPosts(@Param("viewer") Long viewer, @Param("ids") List<Long> ids);
    @Modifying @Query("delete from FeedComment c where c.post.id = :postId")
    void deleteForPost(@Param("postId") Long postId);
    @Modifying @Query("update FeedComment c set c.content = '', c.deletedAt = :now "
            + "where c.deletedAt is null and (c.author.deletedAt is not null or c.post.deletedAt is not null)")
    void eraseWithdrawn(@Param("now") java.time.Instant now);
}
