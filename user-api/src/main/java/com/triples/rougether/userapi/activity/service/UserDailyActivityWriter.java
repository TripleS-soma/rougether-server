package com.triples.rougether.userapi.activity.service;

import com.triples.rougether.domain.activity.repository.UserDailyActivityRepository;
import com.triples.rougether.userapi.activity.service.UserDailyActivityBatcher.Activity;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Set<Activity> recordBatch(List<Activity> activities) {
        if (activities.size() > 256) throw new IllegalArgumentException("활동 batch는 최대 256건입니다");
        var usersByDate = new TreeMap<LocalDate, Set<Long>>();
        for (Activity activity : activities) {
            usersByDate.computeIfAbsent(activity.date(), ignored -> new TreeSet<>()).add(activity.userId());
        }
        Set<Activity> recorded = new HashSet<>();
        usersByDate.forEach((date, users) -> {
            activityRepository.insertBatchIfActiveUsers(users, date);
            // 총 영향 행 수로 사용자별 성공을 추측하지 않음. 제외 대상에는 완료 캐시를 남기지 않음.
            activityRepository.findRecordedActiveUserIds(users, date)
                    .forEach(userId -> recorded.add(new Activity(userId, date)));
        });
        return recorded;
    }
}
