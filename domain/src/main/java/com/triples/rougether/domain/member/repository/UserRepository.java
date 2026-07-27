package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.LockModeType;
import java.time.Instant;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // 회원 탈퇴 - soft delete 와 개인정보 비움을 원자적 1회성 UPDATE 로 한다.
    // 탈퇴 앞단에서 users 행 락을 잡으면, 집 소유권 양도(house.owner_user_id UPDATE)가 fk_house_owner 로
    // 양도 대상의 users 행에 shared lock 을 잡기 때문에 서로의 집에 함께 속한 두 사람의 동시 탈퇴가
    // 데드락에 빠진다. users 쓰기를 마지막 한 번으로 미루고, 중복 탈퇴는 영향행 0으로 판별한다
    // (동시 요청이 들어오면 한쪽만 1, 진 쪽은 0 → 이미 탈퇴로 처리).
    // flushAutomatically 가 없으면 앞선 집 정리(house_members 상태 전환)가 flush 되기 전에
    // clear 로 detach 되어 통째로 유실된다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.deletedAt = :now, u.updatedAt = :now, u.nickname = null, u.email = null, "
            + "u.profileImageKey = null, u.bio = null where u.id = :id and u.deletedAt is null")
    int withdrawIfActive(@Param("id") Long id, @Param("now") Instant now);

    // 어드민 유저 검색(읽기 전용) - email/nickname 부분 일치(대소문자 무시), 검색어 없으면 전체.
    @Query("select u from User u where :query is null "
            + "or lower(u.email) like lower(concat('%', :query, '%')) "
            + "or lower(u.nickname) like lower(concat('%', :query, '%'))")
    Page<User> searchForAdmin(@Param("query") String query, Pageable pageable);
}
