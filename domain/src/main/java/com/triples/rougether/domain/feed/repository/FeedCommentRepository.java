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
    @Query("select c.author.id from FeedComment c where c.id = :id and c.post.id = :postId")
    Optional<Long> findAuthorId(@Param("postId") Long postId, @Param("id") Long id);

    // 차단한 원댓글의 대화는 통째로 숨기고, 삭제·탈퇴한 원댓글은 답글이 남아 있으면 자리만 유지함.
    String UNBLOCKED = "not exists (select b.id from UserBlock b where b.blockerUserId = :viewer "
            + "and b.blockedUserId = c.author.id) ";
    String THREAD_VISIBLE = "and (c.parentCommentId is null or exists (select root.id from FeedComment root "
            + "where root.id = c.parentCommentId and root.post.id = c.post.id "
            + "and not exists (select rb.id from UserBlock rb where rb.blockerUserId = :viewer "
            + "and rb.blockedUserId = root.author.id))) ";
    String LIVE_VISIBLE = "c.deletedAt is null and c.author.deletedAt is null and " + UNBLOCKED + THREAD_VISIBLE;

    // 기존 전체 댓글 목록은 오래된 ID 순서와 삭제 제외 계약을 유지함.
    @Query("select c from FeedComment c join fetch c.author where c.post.id = :postId and "
            + LIVE_VISIBLE + "and (:after is null or c.id > :after) order by c.id")
    List<FeedComment> findPage(@Param("viewer") Long viewer, @Param("postId") Long postId,
                               @Param("after") Long after, Pageable page);

    @Query("select c from FeedComment c join fetch c.author where c.post.id = :postId "
            + "and c.parentCommentId is null and " + UNBLOCKED
            + "and ((c.deletedAt is null and c.author.deletedAt is null) or exists "
            + "(select r.id from FeedComment r where r.parentCommentId = c.id and r.post.id = c.post.id "
            + "and r.deletedAt is null and r.author.deletedAt is null and not exists "
            + "(select rb.id from UserBlock rb where rb.blockerUserId = :viewer and rb.blockedUserId = r.author.id))) "
            + "and (:after is null or c.id > :after) order by c.id")
    List<FeedComment> findThreads(@Param("viewer") Long viewer, @Param("postId") Long postId,
                                  @Param("after") Long after, Pageable page);

    @Query("select c from FeedComment c join fetch c.author where c.post.id = :postId "
            + "and c.parentCommentId = :parentId and " + LIVE_VISIBLE
            + "and (:after is null or c.id > :after) order by c.id")
    List<FeedComment> findReplies(@Param("viewer") Long viewer, @Param("postId") Long postId,
                                  @Param("parentId") Long parentId, @Param("after") Long after, Pageable page);

    @Query("select c.parentCommentId as parentCommentId, count(c) as total from FeedComment c "
            + "where c.parentCommentId in :ids and " + LIVE_VISIBLE + "group by c.parentCommentId")
    List<FeedReplyCount> countForParents(@Param("viewer") Long viewer, @Param("ids") List<Long> ids);

    @Query("select c.post.id as postId, count(c) as total from FeedComment c where c.post.id in :ids and "
            + LIVE_VISIBLE + "group by c.post.id")
    List<FeedCount> countForPosts(@Param("viewer") Long viewer, @Param("ids") List<Long> ids);
    @Modifying @Query("delete from FeedComment c where c.post.id = :postId")
    void deleteForPost(@Param("postId") Long postId);
    @Modifying @Query("update FeedComment c set c.content = '', c.deletedAt = :now "
            + "where c.deletedAt is null and (c.author.deletedAt is not null or c.post.deletedAt is not null)")
    void eraseWithdrawn(@Param("now") java.time.Instant now);
}
