package com.triples.rougether.userapi.activity.service;

import com.triples.rougether.domain.activity.repository.UserDailyActivityRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserDailyActivityWriter {

    private final UserDailyActivityRepository activityRepository;

    // 컨트롤러 비즈니스 트랜잭션과 성공·실패를 공유하지 않는 짧은 관측 트랜잭션임.
    // 반환값은 영향 row 수 - 0 이면 대상 아님(탈퇴·봇)으로 기록되지 않은 것.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int record(Long userId, LocalDate activityDate) {
        return activityRepository.insertIfActiveUser(userId, activityDate);
    }
}
