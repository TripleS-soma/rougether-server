package com.triples.rougether.userapi.aiuser.service;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.policy.SignupWalletPolicy;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiUserSeedService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final UserWalletRepository wallets;
    private final WalletHistoryRecorder history;
    private final HouseCommandService houses;
    private final Clock clock;

    // singleton을 먼저 잠가 두 시드 요청이 계정/지갑을 중복 생성하지 않게 함. 기존 봇 행은 수정하지 않음.
    @Transactional
    public List<Long> seed() {
        jdbc.queryForObject("SELECT id FROM ai_user_runtime WHERE id=1 FOR UPDATE", Integer.class);
        List<Long> ids = new ArrayList<>();
        for (var persona : AiPersonas.all()) {
            List<Long> previous = jdbc.queryForList("SELECT user_id FROM ai_user_profiles WHERE ai_key=?", Long.class, persona.key());
            if (!previous.isEmpty()) { ids.add(previous.getFirst()); continue; }
            User user = users.saveAndFlush(User.ai(persona.nickname(), persona.bio()));
            history.recordSignupBonus(wallets.saveAll(SignupWalletPolicy.issueAll(user)));
            houses.createStarterHouse(user);
            var now = Timestamp.from(clock.instant());
            jdbc.update("INSERT INTO ai_user_profiles(user_id,ai_key,persona,next_run_at,created_at) VALUES (?,?,?,?,?)",
                    user.getId(), persona.key(), persona.instructions(), now, now);
            ids.add(user.getId());
        }
        return ids;
    }
}
