package com.triples.rougether.userapi.bot;

import com.triples.rougether.userapi.bot.BotSeedService.Outcome;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

// 동거 봇 카탈로그 전체를 순회하며 BotSeedService.seed 를 프록시 경유로 한 봇씩 호출한다(#307).
// 트랜잭션 밖에서 호출되어야 봇 1명 = 트랜잭션 1개가 성립하고, 한 봇의 실패가 다른 봇을 롤백시키지 않는다.
// 기동 경로(BotSeeder)는 트랜잭션 없이 호출한다. 테스트가 @Transactional 안에서 호출하면 전부 한 트랜잭션에 참여한다.
@Slf4j
@Component
public class BotSeedRunner {

    private final BotSeedService botSeedService;

    public BotSeedRunner(BotSeedService botSeedService) {
        this.botSeedService = botSeedService;
    }

    public SeedResult seedAll() {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        List<BotProfile> profiles = BotProfileCatalog.PROFILES;
        for (int index = 0; index < profiles.size(); index++) {
            BotProfile profile = profiles.get(index);
            try {
                Outcome outcome = botSeedService.seed(profile, index);
                switch (outcome) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                    default -> throw new IllegalStateException("처리되지 않은 시드 결과: " + outcome);
                }
            } catch (DataIntegrityViolationException e) {
                if (botSeedService.exists(profile.botKey())) {
                    // bot_key UNIQUE 경합 — 동시 기동(재시작 겹침·롤링 배포)에서 다른 인스턴스가 먼저 만든 경우.
                    // 승자 데이터가 정본이므로 갱신으로 집계하고 스택 없이 알린다.
                    updated++;
                    log.info("동거 봇 시드 경합 - 다른 인스턴스가 먼저 생성함, botKey={}", profile.botKey());
                } else {
                    // 경합이 아닌 제약 위반(컬럼 길이 등 카탈로그 버그)은 숨기지 않는다.
                    failed++;
                    log.warn("동거 봇 시드 실패(제약 위반) - botKey={}", profile.botKey(), e);
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("동거 봇 시드 실패 - botKey={}", profile.botKey(), e);
            }
        }
        return new SeedResult(created, updated, skipped, failed);
    }

    public record SeedResult(int created, int updated, int skipped, int failed) {
    }
}
