package com.triples.rougether.domain.house.repository;

import com.triples.rougether.domain.house.entity.HouseMission;
import com.triples.rougether.domain.house.entity.HouseMissionStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HouseMissionRepository extends JpaRepository<HouseMission, Long> {

    List<HouseMission> findByHouseIdAndStatus(Long houseId, String status);

    // 집 미션 목록 - 최신 생성순.
    List<HouseMission> findByHouseIdOrderByCreatedAtDescIdDesc(Long houseId);

    // 루틴 완료 기여 적립용 - 유저가 속한 여러 집의 진행 중 미션 일괄 조회.
    List<HouseMission> findByHouseIdInAndStatus(Collection<Long> houseIds, HouseMissionStatus status);

    Optional<HouseMission> findByIdAndHouseId(Long id, Long houseId);

    // claim 경로 전용 - 행 락으로 동시 claim 의 성장 포인트 이중 지급을 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from HouseMission m where m.id = :missionId and m.house.id = :houseId")
    Optional<HouseMission> findWithLockByIdAndHouseId(@Param("missionId") Long missionId,
                                                      @Param("houseId") Long houseId);
}
