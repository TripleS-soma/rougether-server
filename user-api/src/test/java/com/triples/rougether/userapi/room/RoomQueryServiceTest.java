package com.triples.rougether.userapi.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.entity.PersonalRoom;
import com.triples.rougether.domain.room.repository.PersonalRoomRepository;
import com.triples.rougether.domain.room.repository.RoomSurfaceSlotRepository;
import com.triples.rougether.domain.routine.entity.Streak;
import com.triples.rougether.domain.routine.repository.StreakRepository;
import com.triples.rougether.userapi.room.dto.RoomResponse;
import com.triples.rougether.userapi.room.service.RoomQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomQueryServiceTest {

    @Mock private PersonalRoomRepository personalRoomRepository;
    @Mock private RoomSurfaceSlotRepository roomSurfaceSlotRepository;
    @Mock private StreakRepository streakRepository;
    @Mock private UserCharacterRepository userCharacterRepository;
    @Mock private UserRepository userRepository;
    @InjectMocks private RoomQueryService roomQueryService;

    @Test
    void 방이_없으면_lazy_생성하고_레벨0_스트릭0으로_응답한다() {
        Long userId = 1L;
        when(personalRoomRepository.findById(userId)).thenReturn(Optional.empty());
        when(userRepository.getReferenceById(userId)).thenReturn(mock(User.class));
        PersonalRoom saved = mock(PersonalRoom.class);
        when(saved.getUserId()).thenReturn(userId);
        when(saved.getGrowthLevel()).thenReturn(0);
        when(saved.getUpdatedAt()).thenReturn(Instant.EPOCH);
        when(personalRoomRepository.save(any(PersonalRoom.class))).thenReturn(saved);
        when(roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId)).thenReturn(List.of());
        when(streakRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userCharacterRepository.findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        RoomResponse response = roomQueryService.getMyRoom(userId);

        verify(personalRoomRepository).save(any(PersonalRoom.class));
        assertThat(response.roomUserId()).isEqualTo(userId);
        assertThat(response.growthLevel()).isZero();
        assertThat(response.slots()).isEmpty();
        assertThat(response.streak().currentCount()).isZero();
        assertThat(response.streak().longestCount()).isZero();
    }

    @Test
    void 방이_있으면_생성하지_않고_조회만_한다() {
        Long userId = 2L;
        PersonalRoom existing = mock(PersonalRoom.class);
        when(existing.getUserId()).thenReturn(userId);
        when(existing.getGrowthLevel()).thenReturn(5);
        when(existing.getUpdatedAt()).thenReturn(Instant.EPOCH);
        when(personalRoomRepository.findById(userId)).thenReturn(Optional.of(existing));
        when(roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId)).thenReturn(List.of());
        Streak streak = mock(Streak.class);
        when(streak.getCurrentCount()).thenReturn(3);
        when(streak.getLongestCount()).thenReturn(7);
        when(streakRepository.findByUserId(userId)).thenReturn(Optional.of(streak));
        when(userCharacterRepository.findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId)).thenReturn(Optional.empty());

        RoomResponse response = roomQueryService.getMyRoom(userId);

        verify(personalRoomRepository, never()).save(any());
        assertThat(response.growthLevel()).isEqualTo(5);
        assertThat(response.streak().currentCount()).isEqualTo(3);
        assertThat(response.streak().longestCount()).isEqualTo(7);
    }

    @Test
    void 착용_캐릭터가_있으면_응답에_assetKey까지_포함한다() {
        Long userId = 3L;
        PersonalRoom room = mock(PersonalRoom.class);
        when(room.getUserId()).thenReturn(userId);
        when(room.getGrowthLevel()).thenReturn(1);
        when(room.getUpdatedAt()).thenReturn(Instant.EPOCH);
        when(personalRoomRepository.findById(userId)).thenReturn(Optional.of(room));
        when(roomSurfaceSlotRepository.findByRoomUserIdWithItem(userId)).thenReturn(List.of());
        when(streakRepository.findByUserId(userId)).thenReturn(Optional.empty());
        Character character = mock(Character.class);
        when(character.getId()).thenReturn(5L);
        when(character.getCode()).thenReturn("bear");
        when(character.getName()).thenReturn("Bear");
        when(character.getBaseAssetKey()).thenReturn("characters/bear.png");
        UserCharacter selected = mock(UserCharacter.class);
        when(selected.getCharacter()).thenReturn(character);
        when(userCharacterRepository.findByUserIdAndSelectedIsTrueAndDeletedAtIsNull(userId))
                .thenReturn(Optional.of(selected));

        RoomResponse response = roomQueryService.getMyRoom(userId);

        assertThat(response.character()).isNotNull();
        assertThat(response.character().characterId()).isEqualTo(5L);
        assertThat(response.character().code()).isEqualTo("bear");
        assertThat(response.character().assetKey()).isEqualTo("characters/bear.png");
    }
}
