package com.triples.rougether.domain.member.policy;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.shared.CurrencyType;
import java.util.Arrays;
import java.util.List;

// 가입 시 지갑 발급 정책. 통화별 지갑을 한 번에 발급하고 초기 잔액을 정한다.
// 초기 코인 500 = 온보딩(튜토리얼)에서 가구 뽑기 단챠(250)를 1회 체험시키고 250을 남기는 값.
// 지급 시점이 가입 트랜잭션(지갑 신규 발급)이라 (user_id, currency_type) UNIQUE 제약이 중복 지급을 막는다.
public final class SignupWalletPolicy {

    public static final int INITIAL_COIN_BALANCE = 500;

    private SignupWalletPolicy() {
    }

    public static List<UserWallet> issueAll(User user) {
        return Arrays.stream(CurrencyType.values())
                .map(type -> UserWallet.createWithBalance(user, type, initialBalanceOf(type)))
                .toList();
    }

    public static int initialBalanceOf(CurrencyType currencyType) {
        return currencyType == CurrencyType.COIN ? INITIAL_COIN_BALANCE : 0;
    }
}
