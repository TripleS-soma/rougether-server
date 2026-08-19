package com.triples.rougether.userapi.auth.service;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 회원가입 공통 지급(#322): 유저 저장 → 통화별 지갑 + 가입 보너스 원장 → 기본 집(나의 집, 봇 2명, 목표 없음).
// dev-login 신규·카카오·구글·애플 최초 로그인 4경로가 모두 이걸 쓴다 — 호출자의 로그인 트랜잭션에 합류해
// 한 번에 커밋/롤백되므로 "지갑은 있는데 집이 없는" 계정이 생기지 않는다. 동거 봇 계정(User.bot)은 이 경로를 타지 않는다.
@Service
@RequiredArgsConstructor
public class SignupService {

    private final UserRepository userRepository;
    private final UserWalletRepository userWalletRepository;
    private final WalletHistoryRecorder walletHistoryRecorder;
    private final HouseCommandService houseCommandService;

    // email 은 provider 가 준 값(없으면 null). IDENTITY 전략이라 user 는 여기서 즉시 INSERT 된다
    @Transactional
    public User register(String email) {
        User user = userRepository.save(User.signUp(email));
        // 가입 시 통화별 지갑을 함께 발급(COIN=완료 보상, DIAMOND=구매). 초기 잔액은 SignupWalletPolicy 소관.
        // 가입 보너스는 재화 원장에도 기록함(#253).
        walletHistoryRecorder.recordSignupBonus(
                userWalletRepository.saveAll(SignupWalletPolicy.issueAll(user)));
        // 기본 집은 가입과 함께 무조건 지급 — 온보딩 호출 순서·경로에 기대지 않는다(#322)
        houseCommandService.createStarterHouse(user);
        return user;
    }
}
