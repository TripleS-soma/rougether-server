package com.triples.rougether.userapi.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 동거 봇 활동 스케줄러(#310) — 10분마다 BotActivityService.runTick(). rougether.bots.enabled=true 일 때만 등록된다
// (기본 false — 테스트 컨텍스트도 false 라 틱이 돌지 않는다).
// 멀티 인스턴스 미지원: 단일 EC2 전제(다른 user-api 스케줄러와 동일한 한계). 인스턴스를 늘리면 같은 틱을 두 번 처리해
// 중복 완료 시도(→ ALREADY_COMPLETED 등 비즈니스 예외로 흡수)가 늘어나므로, 그때 DB 1행 분산 락을 추가한다.
// 예외는 틱 안에서 행동 단위로 격리되므로 여기서는 진입/종료 로그만 남기고, 예기치 못한 예외도 스케줄 스레드를 죽이지 않게 잡는다.
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rougether.bots", name = "enabled", havingValue = "true")
public class BotActivityScheduler {

    private final BotActivityService botActivityService;

    public BotActivityScheduler(BotActivityService botActivityService) {
        this.botActivityService = botActivityService;
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void tick() {
        log.info("동거 봇 활동 틱 시작");
        try {
            BotTickReport report = botActivityService.runTick();
            log.info("동거 봇 활동 틱 종료 - {}", report);
        } catch (RuntimeException e) {
            log.error("동거 봇 활동 틱 중단 - 다음 틱에서 재시도", e);
        }
    }
}
