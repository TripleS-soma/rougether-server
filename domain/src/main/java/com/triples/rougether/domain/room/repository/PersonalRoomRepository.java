package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.PersonalRoom;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonalRoomRepository extends JpaRepository<PersonalRoom, Long> {

    // layout 저장 전용 - 행 락으로 같은 방의 동시 저장을 직렬화해 revision 비교(CAS)를 확실하게 한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PersonalRoom r where r.userId = :userId")
    Optional<PersonalRoom> findWithLockById(@Param("userId") Long userId);
}
