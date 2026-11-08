package com.triples.rougether.userapi.routine.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.triples.rougether.domain.room.repository.RoomFurnitureBonusRepository;
import java.time.*;
import org.junit.jupiter.api.Test;

class ChristmasFurnitureBonusServiceTest {
    private final RoomFurnitureBonusRepository rooms = mock(RoomFurnitureBonusRepository.class);
    private final ChristmasFurnitureBonusService bonus = new ChristmasFurnitureBonusService(rooms, true);

    @Test
    void 플래그가_꺼졌거나_원금이_없거나_기간밖이면_배치를_조회하지_않는다() {
        assertThat(new ChristmasFurnitureBonusService(rooms, false).bonusCoins(1L, LocalDate.of(2026, 11, 9), 10)).isZero();
        assertThat(bonus.bonusCoins(1L, LocalDate.of(2026, 11, 9), 0)).isZero();
        assertThat(bonus.bonusCoins(1L, LocalDate.of(2026, 11, 8), 10)).isZero();
        assertThat(bonus.bonusCoins(1L, LocalDate.of(2026, 12, 26), 10)).isZero();
        verifyNoInteractions(rooms);
    }

    @Test
    void 시작일과_종료일을_포함하고_부분원금은_마지막에_정수버림한다() {
        when(rooms.countFreeLayoutFurnitureKinds(1L, "snowy_christmas_202611")).thenReturn(3L);
        assertThat(bonus.bonusCoins(1L, LocalDate.of(2026, 11, 9), 10)).isEqualTo(3);
        assertThat(bonus.bonusCoins(1L, LocalDate.of(2026, 12, 25), 5)).isEqualTo(1);
    }

    @Test
    void 등록종류가_늘어도_종류별_10퍼센트를_계산한다() {
        when(rooms.countFreeLayoutFurnitureKinds(anyLong(), anyString())).thenReturn(9L);
        assertThat(bonus.bonusCoins(1L, LocalDate.of(2026, 11, 9), 10)).isEqualTo(9);
    }

    @Test
    void 완료시각은_Clock의_타임존과_무관하게_KST로_한번만_판정한다() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-08T15:00:00Z"), Instant.parse("2026-12-25T15:00:00Z"));
        var rewards = new CompletionRewardService(mock(DailyRewardService.class), null, bonus, clock);
        var start = rewards.completionTime();
        assertThat(start.today()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(start.completedAt()).isEqualTo(Instant.parse("2026-11-08T15:00:00Z"));
        var end = rewards.completionTime();
        assertThat(end.today()).isEqualTo(LocalDate.of(2026, 12, 26));
        assertThat(bonus.bonusCoins(1L, end.today(), 10)).isZero();
        verify(clock, times(2)).instant();
        verifyNoInteractions(rooms);
    }
}
