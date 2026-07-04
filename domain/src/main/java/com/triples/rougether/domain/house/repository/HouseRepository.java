package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.House;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseRepository extends JpaRepository<House, Long> {

    Optional<House> findByInviteCode(String inviteCode);

    // 참여(정원 검사 + 구성원 수 증가) 경로 전용 - 행 락으로 동시 참여의 정원 초과를 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from House h where h.inviteCode = :inviteCode")
    Optional<House> findWithLockByInviteCode(@Param("inviteCode") String inviteCode);

    boolean existsByInviteCode(String inviteCode);
}
