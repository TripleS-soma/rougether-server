package com.triples.rougether.domain.market.repository;

import com.triples.rougether.domain.market.entity.MarketEngineLease;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface MarketEngineLeaseRepository extends JpaRepository<MarketEngineLease, Byte> {

    // 엔진 처리 트랜잭션의 첫 잠금. 순번 부여와(#402) 펜싱 확인을 이 행으로 직렬화함.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from MarketEngineLease l where l.id = 1")
    Optional<MarketEngineLease> findForUpdate();
}
