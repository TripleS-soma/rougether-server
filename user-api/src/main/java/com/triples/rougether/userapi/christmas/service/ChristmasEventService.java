package com.triples.rougether.userapi.christmas.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.attendance.entity.AttendanceMode;
import com.triples.rougether.domain.attendance.repository.AttendanceEventRepository;
import com.triples.rougether.domain.christmas.entity.EventPopupDismissal;
import com.triples.rougether.domain.christmas.repository.EventPopupDismissalRepository;
import com.triples.rougether.domain.gacha.repository.GachaRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.christmas.dto.ChristmasEventResponse;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.gacha.service.GachaService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChristmasEventService {
    private final ChristmasEventPolicy policy;
    private final AttendanceEventRepository attendanceEvents;
    private final GachaRepository gachas;
    private final EventPopupDismissalRepository dismissals;
    private final UserRepository users;
    private final Clock kstClock;
    private final GachaService gachaService;

    public ChristmasEventResponse get(Long userId) {
        Instant now = kstClock.instant();
        LocalDate today = LocalDate.ofInstant(now, kstClock.getZone());
        var attendance = attendanceEvents.findByCode(ChristmasEventPolicy.CODE).orElse(null);
        var gacha = gachas.findByCode(ChristmasEventPolicy.CODE).orElse(null);
        boolean ready = policy.isOpen(now)
                && attendance != null && attendance.getMode() == AttendanceMode.CUMULATIVE
                && attendance.isActiveOn(today) && gacha != null && gachaService.isChristmasReady(gacha, now);
        return new ChristmasEventResponse(ChristmasEventPolicy.CODE, "크리스마스", ready,
                ChristmasEventPolicy.STARTS_ON, ChristmasEventPolicy.ENDS_ON,
                gacha == null ? null : gacha.getId(), attendance == null ? null : attendance.getId(),
                dismissals.existsByUserIdAndEventCode(userId, ChristmasEventPolicy.CODE));
    }

    @Transactional
    public void dismissPopup(Long userId) {
        Instant now = kstClock.instant();
        // 계정 잠금과 unique 제약으로 동시 요청/통신 실패 후 재시도를 같은 숨김 기록으로 수렴시킴.
        var user = users.findByIdForUpdate(userId).filter(candidate -> !candidate.isDeleted())
                .orElseThrow(() -> new BusinessException(MemberErrorCode.USER_NOT_FOUND));
        if (!dismissals.existsByUserIdAndEventCode(userId, ChristmasEventPolicy.CODE)) {
            dismissals.save(new EventPopupDismissal(user, ChristmasEventPolicy.CODE, now));
        }
    }
}
