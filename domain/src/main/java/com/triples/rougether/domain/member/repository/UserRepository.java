package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    // 어드민 유저 검색(읽기 전용) - email/nickname 부분 일치(대소문자 무시), 검색어 없으면 전체.
    @Query("select u from User u where :query is null "
            + "or lower(u.email) like lower(concat('%', :query, '%')) "
            + "or lower(u.nickname) like lower(concat('%', :query, '%'))")
    Page<User> searchForAdmin(@Param("query") String query, Pageable pageable);
}
