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
    // 엔티티 dirty checking 대신 단일 컬럼 UPDATE 로 동시 refresh 경합·불필요한 전체 row 갱신을 피함.
    @Modifying
    @Query("update User u set u.lastAccessedAt = :now where u.id = :id")
    int updateLastAccessedAt(@Param("id") Long id, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // 어드민 유저 검색(읽기 전용) - email/nickname 부분 일치(대소문자 무시), 검색어 없으면 전체.
    @Query("select u from User u where :query is null "
            + "or lower(u.email) like lower(concat('%', :query, '%')) "
            + "or lower(u.nickname) like lower(concat('%', :query, '%'))")
    Page<User> searchForAdmin(@Param("query") String query, Pageable pageable);
}
