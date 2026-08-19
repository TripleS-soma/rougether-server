package com.triples.rougether.userapi.routine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.Routine;
import com.triples.rougether.domain.routine.repository.CategoryRepository;
import com.triples.rougether.domain.routine.repository.RoutineRepository;
import com.triples.rougether.userapi.house.support.HouseLinkValidator;
import com.triples.rougether.userapi.routine.dto.RoutineCreateRequest;
import com.triples.rougether.userapi.routine.error.RoutineErrorCode;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

// 임포트 동시 요청 경합: 사전 exists 조회를 둘 다 통과한 뒤 한쪽이 unique 에 막히는 경우 — 409 변환과 그 외 무결성 오류 전파
class RoutineServiceImportRaceTest {

    private static final Long USER_ID = 7L;
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    private final RoutineRepository routineRepository = mock(RoutineRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);

    private final RoutineService service = new RoutineService(routineRepository, mock(CategoryRepository.class),
            userRepository, mock(HouseLinkValidator.class));

    @Test
    void 사전_조회를_통과했어도_unique_위반이면_ROUTINE_EXTERNAL_DUPLICATE로_변환한다() {
        stubNoDuplicate();
        when(routineRepository.saveAndFlush(any(Routine.class)))
                .thenThrow(new DataIntegrityViolationException("uk_routines_user_external"));

        assertDuplicate();
    }

    @Test
    void 하이버네이트가_제약_이름을_알려주면_그것으로_중복을_판정한다() {
        stubNoDuplicate();
        when(routineRepository.saveAndFlush(any(Routine.class))).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("dup", new SQLException("Duplicate entry"), "routines.uk_routines_user_external")));

        assertDuplicate();
    }

    @Test
    void 중복이_아닌_무결성_오류는_409로_위장하지_않고_그대로_던진다() {
        stubNoDuplicate();
        DataIntegrityViolationException truncation = new DataIntegrityViolationException(
                "could not execute statement", new SQLException("Data too long for column 'external_id'"));
        when(routineRepository.saveAndFlush(any(Routine.class))).thenThrow(truncation);

        assertThatThrownBy(() -> service.create(USER_ID, imported("series-1"))).isSameAs(truncation);
    }

    @Test
    void 사전_조회에서_중복이면_저장을_시도하지_않는다() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(routineRepository.existsByUserIdAndExternalSourceAndExternalId(USER_ID, "GOOGLE_CALENDAR", "series-1"))
                .thenReturn(true);

        assertDuplicate();
        verify(routineRepository, never()).saveAndFlush(any(Routine.class));
        verify(routineRepository, never()).save(any(Routine.class));
    }

    @Test
    void 한쪽이_공백_문자열이면_ROUTINE_EXTERNAL_REF_INCOMPLETE로_거절하고_조회도_하지_않는다() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));

        assertThatThrownBy(() -> service.create(USER_ID, new RoutineCreateRequest("PT", null, AuthType.CHECK,
                "DAILY", null, null, TODAY, null, null, "GOOGLE_CALENDAR", "  ")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_EXTERNAL_REF_INCOMPLETE);
        verify(routineRepository, never()).existsByUserIdAndExternalSourceAndExternalId(any(), any(), any());
        verify(routineRepository, never()).save(any(Routine.class));
    }

    @Test
    void externalSource와_externalId는_앞뒤_공백을_떼고_조회_저장한다() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(routineRepository.existsByUserIdAndExternalSourceAndExternalId(eq(USER_ID), any(), any())).thenReturn(false);
        when(routineRepository.saveAndFlush(any(Routine.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(USER_ID, new RoutineCreateRequest("PT", null, AuthType.CHECK, "DAILY", null, null,
                TODAY, null, null, " GOOGLE_CALENDAR ", "  series-1 "));

        verify(routineRepository).existsByUserIdAndExternalSourceAndExternalId(USER_ID, "GOOGLE_CALENDAR", "series-1");
        ArgumentCaptor<Routine> saved = ArgumentCaptor.forClass(Routine.class);
        verify(routineRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getExternalSource()).isEqualTo("GOOGLE_CALENDAR");
        assertThat(saved.getValue().getExternalId()).isEqualTo("series-1");
    }

    private void stubNoDuplicate() {
        when(userRepository.getReferenceById(USER_ID)).thenReturn(mock(User.class));
        when(routineRepository.existsByUserIdAndExternalSourceAndExternalId(USER_ID, "GOOGLE_CALENDAR", "series-1"))
                .thenReturn(false);
    }

    private void assertDuplicate() {
        assertThatThrownBy(() -> service.create(USER_ID, imported("series-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(RoutineErrorCode.ROUTINE_EXTERNAL_DUPLICATE);
    }

    private static RoutineCreateRequest imported(String externalId) {
        return new RoutineCreateRequest("PT", null, AuthType.CHECK, "DAILY", null, null, TODAY, null, null,
                "GOOGLE_CALENDAR", externalId);
    }
}
