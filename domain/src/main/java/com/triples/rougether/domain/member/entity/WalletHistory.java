package com.triples.rougether.domain.member.entity;

import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 재화 증감 원장(#253). 적립·차감을 모두 기록하고 수정하지 않음.
// 단 루틴/투두 완료 취소는 회수 row 를 남기지 않고 원 획득 row 를 삭제함 — source_type/source_id 로 특정.
// balance_after 는 증감 직후 잔액 스냅샷 — 원 row 삭제 정책과 조합 시 이후 row 의 스냅샷이
// 사후 재계산과 다를 수 있음(확정 사양, #253 결정값).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "wallet_histories")
public class WalletHistory extends BaseCreatedEntity {

    public static final String SOURCE_ROUTINE_LOG = "ROUTINE_LOG";
    public static final String SOURCE_TODO = "TODO";
    public static final String SOURCE_GACHA = "GACHA";
    public static final String SOURCE_ITEM = "ITEM";
    public static final String SOURCE_ROOM_COBWEB = "ROOM_COBWEB";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_type", length = 30, nullable = false)
    private CurrencyType currencyType;

    // 적립 양수, 차감 음수. 지급액 0 인 이벤트는 기록하지 않음
    @Column(name = "amount", nullable = false)
    private int amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30, nullable = false)
    private WalletHistoryReason reason;

    @Column(name = "balance_after", nullable = false)
    private int balanceAfter;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    private WalletHistory(User user, CurrencyType currencyType, int amount,
                          WalletHistoryReason reason, int balanceAfter,
                          String sourceType, Long sourceId) {
        this.user = user;
        this.currencyType = currencyType;
        this.amount = amount;
        this.reason = reason;
        this.balanceAfter = balanceAfter;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
    }

    public static WalletHistory record(User user, CurrencyType currencyType, int amount,
                                       WalletHistoryReason reason, int balanceAfter,
                                       String sourceType, Long sourceId) {
        if (amount == 0) {
            throw new IllegalArgumentException("증감액 0 은 기록 대상이 아님");
        }
        return new WalletHistory(user, currencyType, amount, reason, balanceAfter, sourceType, sourceId);
    }
}
