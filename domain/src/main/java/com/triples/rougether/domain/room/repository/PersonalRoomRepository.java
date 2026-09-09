package com.triples.rougether.domain.room.repository;

import com.triples.rougether.domain.room.entity.PersonalRoom;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersonalRoomRepository extends JpaRepository<PersonalRoom, Long> {

    // 빈 방의 동시 생성은 PK upsert 로 직렬화함. 기존 성장·배치·updated_at 은 덮어쓰지 않음.
    // insert 전에 영속성 컨텍스트의 신규 회원을 flush 하되, 관리 중인 완료/지갑 엔티티는 detach 하지 않음.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO personal_rooms (user_id, growth_level, growth_points, layout_format, layout_revision, updated_at)
            VALUES (:userId, 0, 0, 'SLOT_V1', 0, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE user_id = personal_rooms.user_id
            """, nativeQuery = true)
    void ensureExists(@Param("userId") Long userId);

    // 성장 반영과 layout 저장이 같은 방 행 락을 사용해 서로의 변경을 덮어쓰지 않음.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PersonalRoom r where r.userId = :userId")
    Optional<PersonalRoom> findWithLockById(@Param("userId") Long userId);
}
