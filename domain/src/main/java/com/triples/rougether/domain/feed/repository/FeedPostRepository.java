package com.triples.rougether.domain.feed.repository;

import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.entity.FeedBoardType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
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

    // 집 글은 요청자가 그 집의 ACTIVE 구성원(일반 회원)이고 집이 삭제되지 않았을 때만 보임(#528).
    // 봇·AI 계정은 구성원이어도 집 피드에 참여하지 않음.
    String HOUSE_VISIBLE = "(p.houseId is null or exists (select hm.id from HouseMember hm join hm.house h join hm.user mu "
            + "where h.id = p.houseId and mu.id = :viewer and h.deletedAt is null "
            + "and hm.status = com.triples.rougether.domain.house.entity.HouseMemberStatus.ACTIVE "
            + "and mu.deletedAt is null and mu.bot = false and mu.ai = false)) ";

    // 요청자(viewer)가 차단한 작성자의 글은 NOT EXISTS 로 빼서 커서 페이지 크기를 유지함(#399).
    // houseId 가 null 이면 공개 글만, 값이 있으면 그 집 글만 조회함. 집 조회의 구성원 검사는 HOUSE_VISIBLE 이 다시 막음.
    @Query("select p from FeedPost p join fetch p.author a where p.deletedAt is null and a.deletedAt is null "
            + "and (:authorId is null or a.id = :authorId) and (:before is null or p.id < :before) "
            + "and (:boardType is null or p.boardType = :boardType) "
            + "and ((:houseId is null and p.houseId is null) or p.houseId = :houseId) and " + HOUSE_VISIBLE
            + "and not exists (select b.id from UserBlock b where b.blockerUserId = :viewer and b.blockedUserId = a.id) "
            + "order by p.id desc")
    List<FeedPost> findPage(@Param("viewer") Long viewer, @Param("authorId") Long authorId,
                            @Param("before") Long before, @Param("boardType") FeedBoardType boardType,
                            @Param("houseId") Long houseId, Pageable page);
    @Query("select p from FeedPost p join fetch p.author a where p.id = :id and p.deletedAt is null and a.deletedAt is null "
            + "and " + HOUSE_VISIBLE
            + "and not exists (select b.id from UserBlock b where b.blockerUserId = :viewer and b.blockedUserId = a.id)")
    Optional<FeedPost> findVisible(@Param("id") Long id, @Param("viewer") Long viewer);

    // 어드민 피드 관리 목록. 차단·탈퇴 필터 없이 최신순, 삭제된 글은 요청할 때만 포함함.
    @Query(value = "select p from FeedPost p join fetch p.author a "
            + "where (:includeDeleted = true or p.deletedAt is null) order by p.id desc",
            countQuery = "select count(p) from FeedPost p where (:includeDeleted = true or p.deletedAt is null)")
    Page<FeedPost> findAdminPage(@Param("includeDeleted") boolean includeDeleted, Pageable page);

    @Modifying
    @Query("update FeedPost p set p.content = '', p.routineId = null, p.routineDate = null, p.routineTitle = null, "
            + "p.minigameGameCode = null, p.minigameScore = null, p.minigameRank = null, p.minigamePersonalBest = null, p.minigameAchievedAt = null, "
            + "p.deletedAt = :now where p.deletedAt is null and p.author.deletedAt is not null")
    int eraseWithdrawn(@Param("now") java.time.Instant now);

    // 해체된 집의 글을 본인 삭제와 같은 tombstone 으로 만듦(#531). 댓글·좋아요·사진은 삭제된 글을 치우는 기존 정리 단계가 이어서 처리함.
    @Modifying
    @Query("update FeedPost p set p.content = '', p.routineId = null, p.routineDate = null, p.routineTitle = null, "
            + "p.minigameGameCode = null, p.minigameScore = null, p.minigameRank = null, p.minigamePersonalBest = null, p.minigameAchievedAt = null, "
            + "p.deletedAt = :now where p.deletedAt is null "
            + "and p.houseId in (select h.id from House h where h.deletedAt is not null)")
    int eraseDissolvedHouses(@Param("now") java.time.Instant now);

    // 집을 나가며 "내 글도 지우기"를 고른 구성원의 그 집 글을 본인 삭제와 같은 tombstone 으로 만듦.
    // 그 글의 댓글·좋아요·사진은 삭제된 글을 치우는 기존 정리 단계가 처리함.
    @Modifying
    @Query("update FeedPost p set p.content = '', p.routineId = null, p.routineDate = null, p.routineTitle = null, "
            + "p.minigameGameCode = null, p.minigameScore = null, p.minigameRank = null, p.minigamePersonalBest = null, p.minigameAchievedAt = null, "
            + "p.deletedAt = :now where p.deletedAt is null and p.houseId = :houseId and p.author.id = :authorId")
    int eraseAuthoredInHouse(@Param("houseId") Long houseId, @Param("authorId") Long authorId,
                             @Param("now") java.time.Instant now);
}
