package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.WalletHistory;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletHistoryRepository extends JpaRepository<WalletHistory, Long> {

    // 내 이력 조회 — 재화·증감방향은 optional 필터, 최신순(id desc = created_at desc 동치이며 동시각에도 안정).
    // 방향은 amount 부호로 판정함(EARN 양수 / SPEND 음수). earn null 이면 전체.
    @Query("""
            select h from WalletHistory h
            where h.user.id = :userId
              and (:currencyType is null or h.currencyType = :currencyType)
              and (:earn is null or (:earn = true and h.amount > 0) or (:earn = false and h.amount < 0))
            order by h.id desc
            """)
    Page<WalletHistory> findHistories(@Param("userId") Long userId,
                                      @Param("currencyType") CurrencyType currencyType,
                                      @Param("earn") Boolean earn,
                                      Pageable pageable);

    // 완료 취소 시 원 획득 row 삭제용. source_id 는 ROUTINE_LOG/TODO 에 한해 원본 PK 로 유일함
    // (GACHA/ITEM 은 여러 유저가 같은 값을 공유) — 오삭제 방어를 위해 user 스코프를 필수로 둠
    void deleteByUserIdAndReasonAndSourceTypeAndSourceId(Long userId, WalletHistoryReason reason,
                                                         String sourceType, Long sourceId);
}
