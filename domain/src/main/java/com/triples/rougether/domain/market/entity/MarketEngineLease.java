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
// 엔진은 처리 트랜잭션마다 이 값을 확인해 이전 담당의 쓰기를 거부함(#402).
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

    public boolean isOwnedBy(String owner) {
        return owner != null && owner.equals(ownerToken);
    }

    // 주인이 없거나 유효기간이 지났으면 누구나 인수할 수 있음
    public boolean isExpired(Instant now) {
        return ownerToken == null || leaseUntil == null || !leaseUntil.isAfter(now);
    }

    public void extend(Instant until) {
        this.leaseUntil = until;
    }

    // 인수: 주인을 바꾸고 펜싱 번호를 1 올려 이전 담당의 늦은 쓰기를 거부하게 함. 새 번호를 돌려줌.
    public long takeOver(String owner, Instant until) {
        this.ownerToken = owner;
        this.leaseUntil = until;
        this.fencingToken += 1;
        return fencingToken;
    }

    // 스스로 내려놓음(엔진 정체 등). 펜싱 번호는 그대로 두고 다음 인수에서 올림.
    public void release() {
        this.ownerToken = null;
        this.leaseUntil = null;
    }

    // 다음 처리 순번. 잠금 조회한 행에서만 호출함.
    public long nextSeq() {
        this.lastEngineSeq += 1;
        return lastEngineSeq;
    }
}
