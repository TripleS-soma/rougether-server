package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.shared.CurrencyType;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserWalletRepository extends JpaRepository<UserWallet, Long> {

    List<UserWallet> findByUserId(Long userId);

    Optional<UserWallet> findByUserIdAndCurrencyType(Long userId, CurrencyType currencyType);

    // 재화 차감/전환 경로 전용 — 행 락으로 동시 요청의 이중 차감·잔액 유실을 막는다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from UserWallet w where w.user.id = :userId and w.currencyType = :currencyType")
    Optional<UserWallet> findWithLockByUserIdAndCurrencyType(@Param("userId") Long userId,
                                                             @Param("currencyType") CurrencyType currencyType);
}
