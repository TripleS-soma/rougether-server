package com.triples.rougether.userapi.house.event;

import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.routine.event.RoutineCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 루틴 완료 → 단체 미션 기여 적립. 적립 실패가 루틴 완료 자체를 깨지 않도록 격리한다.
@Component
public class HouseMissionRoutineListener {

    private static final Logger log = LoggerFactory.getLogger(HouseMissionRoutineListener.class);

    private final HouseMissionService houseMissionService;

    public HouseMissionRoutineListener(HouseMissionService houseMissionService) {
        this.houseMissionService = houseMissionService;
    }

    @EventListener
    public void onRoutineCompleted(RoutineCompletedEvent event) {
        try {
            houseMissionService.accrueDailyContribution(event.userId());
        } catch (Exception exception) {
            log.warn("루틴 완료 미션 기여 적립 실패 - userId={}", event.userId(), exception);
        }
    }
}
