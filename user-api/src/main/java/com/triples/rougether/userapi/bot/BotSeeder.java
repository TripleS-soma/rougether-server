package com.triples.rougether.userapi.bot;

import com.triples.rougether.userapi.bot.BotSeedRunner.SeedResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 기동 시 동거 봇 카탈로그를 DB 와 동기화한다(#307). rougether.bots.enabled=true 일 때만 등록된다
// (기본 false — 격리(#308)·활동(#310)이 머지되기 전엔 봇을 만들지 않는다. 테스트 컨텍스트도 false).
// 시드 실패는 기동을 막지 않는다 — 봇은 없어도 서비스는 돌아야 한다. 트랜잭션 없이 호출해 봇 1명 = 트랜잭션 1개.
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rougether.bots", name = "enabled", havingValue = "true")
public class BotSeeder {

    private final BotSeedRunner botSeedRunner;

    public BotSeeder(BotSeedRunner botSeedRunner) {
        this.botSeedRunner = botSeedRunner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedOnStartup() {
        try {
            SeedResult result = botSeedRunner.seedAll();
            log.info("동거 봇 시드 완료 - 생성 {}, 갱신 {}, 건너뜀 {}, 실패 {}",
                    result.created(), result.updated(), result.skipped(), result.failed());
        } catch (RuntimeException e) {
            log.error("동거 봇 시드 중단 - 기동은 계속 진행", e);
        }
    }
}
