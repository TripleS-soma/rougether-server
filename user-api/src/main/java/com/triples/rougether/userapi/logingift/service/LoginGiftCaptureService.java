package com.triples.rougether.userapi.logingift.service;

import com.triples.rougether.domain.logingift.entity.LoginGiftParticipation;
import com.triples.rougether.domain.logingift.repository.LoginGiftEventRepository;
import com.triples.rougether.domain.logingift.repository.LoginGiftParticipationRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class LoginGiftCaptureService {
    private final UserRepository users;
    private final LoginGiftEventRepository events;
    private final LoginGiftParticipationRepository participations;

    // 접속 자격을 먼저 독립 커밋함. 이후 지급 트랜잭션 실패나 자정 경계에도 자격이 남음.
    public boolean capture(Long eventId, Long userId, Instant at) {
        // MySQL TIMESTAMP(6)의 반올림으로 자정 직전 접속이 다음 날로 저장되지 않게 함.
        at = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var user = users.findByIdForUpdate(userId)
                .filter(u -> !u.isDeleted() && !u.isBot() && !u.isAi()).orElse(null);
        if (user == null) return false;
        var event = events.findById(eventId).orElseThrow();
        if (!event.accepts(at)) return false;
        if (participations.findForUpdate(eventId, userId).isEmpty()) {
            participations.save(LoginGiftParticipation.record(event, user, at));
        }
        return true;
    }
}
