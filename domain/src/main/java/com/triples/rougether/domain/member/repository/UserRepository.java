package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    // refresh 회전 성공 시 마지막 접속 시각만 targeted update.
    // 엔티티 dirty checking 대신 targeted UPDATE 로 동시 refresh 경합·불필요한 전체 row 갱신을 피함.
    // bulk UPDATE 는 auditing 을 우회하므로 updated_at 을 직접 함께 갱신하고,
    // 여러 기기의 동시 회전이 최신 값을 과거로 되돌리지 않도록 역행 UPDATE 는 무시함.
    @Modifying
    @Query("update User u set u.lastAccessedAt = :now, u.updatedAt = :now "
            + "where u.id = :id and (u.lastAccessedAt is null or u.lastAccessedAt < :now)")
    int updateLastAccessedAt(@Param("id") Long id, @Param("now") Instant now);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    long countByDeletedAtIsNull();

    long countByDeletedAtIsNullAndCreatedAtGreaterThanEqual(Instant createdAt);

    long countByDeletedAtIsNullAndLastAccessedAtGreaterThanEqual(Instant lastAccessedAt);

    // 온보딩 완료 계약: 목표 1개 이상 + 삭제되지 않은 대표 캐릭터 존재.
    // 전체 유저마다 두 EXISTS를 평가하지 않고 대표 캐릭터 보유 유저에서 시작해 요약 조회 비용을 줄임.
    @Query("select count(distinct uc.user.id) from UserCharacter uc "
            + "where uc.user.deletedAt is null "
            + "and uc.selected = true and uc.deletedAt is null "
            + "and exists (select ug.id from UserGoal ug where ug.user = uc.user)")
    long countOnboardingCompletedUsers();

    @Query("select u.id from User u where u.id in :userIds "
            + "and exists (select ug.id from UserGoal ug where ug.user = u) "
            + "and exists (select uc.id from UserCharacter uc "
            + "  where uc.user = u and uc.selected = true and uc.deletedAt is null)")
    List<Long> findOnboardingCompletedUserIds(@Param("userIds") Collection<Long> userIds);

    // 어드민 유저 관측 검색. 활동 시각은 로그인·refresh 기반이라 실시간 접속 상태가 아님.
    @Query("select u from User u where "
            + "(:query is null "
            + "  or lower(u.email) like lower(concat('%', :query, '%')) "
            + "  or lower(u.nickname) like lower(concat('%', :query, '%'))) "
            + "and (:accountStatus = 'ALL' "
            + "  or (:accountStatus = 'ACTIVE' and u.deletedAt is null) "
            + "  or (:accountStatus = 'WITHDRAWN' and u.deletedAt is not null)) "
            + "and (:neverAccessed = false or u.lastAccessedAt is null) "
            + "and (:accessedAfter is null or u.lastAccessedAt >= :accessedAfter) "
            + "and (:onboardingStatus = 'ALL' "
            + "  or (:onboardingStatus = 'COMPLETED' "
            + "    and exists (select ug.id from UserGoal ug where ug.user = u) "
            + "    and exists (select uc.id from UserCharacter uc "
            + "      where uc.user = u and uc.selected = true and uc.deletedAt is null)) "
            + "  or (:onboardingStatus = 'INCOMPLETE' "
            + "    and (not exists (select ug.id from UserGoal ug where ug.user = u) "
            + "      or not exists (select uc.id from UserCharacter uc "
            + "        where uc.user = u and uc.selected = true and uc.deletedAt is null))))")
    Page<User> searchForAdmin(@Param("query") String query,
                              @Param("accountStatus") String accountStatus,
                              @Param("neverAccessed") boolean neverAccessed,
                              @Param("accessedAfter") Instant accessedAfter,
                              @Param("onboardingStatus") String onboardingStatus,
                              Pageable pageable);
}
