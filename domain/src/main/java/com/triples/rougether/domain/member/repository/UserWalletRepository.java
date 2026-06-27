package com.triples.rougether.domain.member.repository;

import com.triples.rougether.domain.member.entity.UserWallet;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserWalletRepository extends JpaRepository<UserWallet, Long> {

    List<UserWallet> findByUserId(Long userId);

    Optional<UserWallet> findByUserIdAndCurrencyType(Long userId, String currencyType);
}
