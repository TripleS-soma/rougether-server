package com.triples.rougether.batch.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 활성화 + 트리거가 쓰는 KST 기준 Clock 제공.
// 스케줄러 스레드·DB 커넥션이 한 시각에 몰리지 않게 트리거마다 분을 나눠 둔다. 새 트리거는 빈 분에 배정한다.
// - 5분 배수(0,5,..,55): 루틴·투두 리마인더(사용자 지정 시각이라 고정)
// - 0: 단체 미션 만료(spec "매시 정각", UPDATE 1문)   - 1: 저녁 미완료 알림   - 2: 주간 회고 push
// - 3: 하루 마감(day-end)   - 4,9,..,59: 거미줄 알림 발송   - 12,42: 고양이 복귀 알림
// - 30: 주간 회고 생성(spec 일요일 00:30)   - 12:30 매일: 거미줄 발생(spec)   - 38: 탈퇴 파기   - 47: 루틴 추천
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public Clock kstClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
