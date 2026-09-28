package com.triples.rougether.domain.market.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 매칭 엔진 담당 리스(#406, 단일 행 id=1). 담당이 바뀔 때마다 fencing_token 을 1 올리고
// 엔진은 처리 트랜잭션마다 이 값을 확인해 이전 담당의 쓰기를 거부함. 리스 인계 메서드는 리스 이슈(#402)에서 추가.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "market_engine_lease")
public class MarketEngineLease {

    public static final byte SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Byte id;

    @Column(name = "owner_token", length = 36)
    private String ownerToken;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "last_engine_seq", nullable = false)
    private long lastEngineSeq;

    // 다음 처리 순번. 잠금 조회한 행에서만 호출함.
    public long nextSeq() {
        this.lastEngineSeq += 1;
        return lastEngineSeq;
    }
}
