package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.goal.repository.GoalRepository;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseGoalRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseUpdateRequest;
import com.triples.rougether.userapi.house.dto.HouseUpdateResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCoverImageCatalog;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.support.InviteCodeGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HouseUpdateSettingsServiceTest {

    @Mock private HouseRepository houseRepository;
    @Mock private HouseMemberRepository houseMemberRepository;
    @Mock private HouseGoalRepository houseGoalRepository;
    @Mock private GoalRepository goalRepository;
    @Mock private UserRepository userRepository;
    @Mock private InviteCodeGenerator inviteCodeGenerator;
    @Mock private HouseCoverImageCatalog houseCoverImageCatalog;
    @InjectMocks private HouseCommandService houseCommandService;

    // updateSettings 의 부분 수정 검증은 실제 엔티티로 확인한다(필드 변경 여부가 핵심이라 mock 부적합).
    private House realHouse() {
        return House.create(mock(User.class), "원래 이름", "원래 소개", "house/old.png", 4,
                "SETTING2", Instant.now().plus(Duration.ofDays(7)));
    }

    private HouseMember ownerMember() {
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        when(member.getRole()).thenReturn(HouseMemberRole.OWNER);
        return member;
    }

    @Test
    void 지정한_필드만_바뀌고_나머지는_유지된다() {
        House house = realHouse();
        HouseMember owner = ownerMember();
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(owner));

        HouseUpdateResponse response = houseCommandService.updateSettings(7L, 1L,
                new HouseUpdateRequest("새 이름", null, null, 6));

        assertThat(house.getName()).isEqualTo("새 이름");
        assertThat(house.getMaxMembers()).isEqualTo(6);
        assertThat(house.getDescription()).isEqualTo("원래 소개"); // 미지정 유지
        assertThat(house.getCoverImageKey()).isEqualTo("house/old.png"); // 미지정 유지
        assertThat(response.name()).isEqualTo("새 이름");
        assertThat(response.maxMembers()).isEqualTo(6);
    }

    @Test
    void 모든_필드를_한_번에_수정할_수_있다() {
        House house = realHouse();
        HouseMember owner = ownerMember();
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(owner));

        houseCommandService.updateSettings(7L, 1L,
                new HouseUpdateRequest("새 이름", "새 소개", "house/new.png", 8));

        assertThat(house.getName()).isEqualTo("새 이름");
        assertThat(house.getDescription()).isEqualTo("새 소개");
        assertThat(house.getCoverImageKey()).isEqualTo("house/new.png");
        assertThat(house.getMaxMembers()).isEqualTo(8);
    }

    @Test
    void 최대_인원은_현재_구성원_수보다_작게_줄일_수_없다() {
        House house = realHouse();
        house.increaseMemberCount();
        house.increaseMemberCount(); // 현재 3명
        HouseMember owner = ownerMember();
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> houseCommandService.updateSettings(7L, 1L,
                new HouseUpdateRequest(null, null, null, 2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_MAX_MEMBERS_BELOW_CURRENT));
        assertThat(house.getMaxMembers()).isEqualTo(4); // 변경 안 됨
    }

    @Test
    void 현재_인원과_같은_값으로는_줄일_수_있다() {
        House house = realHouse();
        house.increaseMemberCount(); // 현재 2명
        HouseMember owner = ownerMember();
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(owner));

        houseCommandService.updateSettings(7L, 1L, new HouseUpdateRequest(null, null, null, 2));

        assertThat(house.getMaxMembers()).isEqualTo(2);
    }

    @Test
    void 빈_요청은_아무것도_바꾸지_않는다() {
        House house = realHouse();
        HouseMember owner = ownerMember();
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(owner));

        HouseUpdateResponse response = houseCommandService.updateSettings(7L, 1L,
                new HouseUpdateRequest(null, null, null, null));

        assertThat(house.getName()).isEqualTo("원래 이름");
        assertThat(house.getMaxMembers()).isEqualTo(4);
        assertThat(response.name()).isEqualTo("원래 이름"); // no-op 이어도 현재 설정을 반환
    }

    @Test
    void 소유자가_아니면_403() {
        House house = realHouse();
        HouseMember member = mock(HouseMember.class);
        when(member.isActive()).thenReturn(true);
        when(member.getRole()).thenReturn(HouseMemberRole.MEMBER);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.of(member));

        assertThatThrownBy(() -> houseCommandService.updateSettings(7L, 1L,
                new HouseUpdateRequest("새 이름", null, null, null)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
        assertThat(house.getName()).isEqualTo("원래 이름");
    }

    @Test
    void 비구성원도_403() {
        House house = realHouse();
        when(houseRepository.findById(1L)).thenReturn(Optional.of(house));
        when(houseMemberRepository.findByHouseIdAndUserId(1L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseCommandService.updateSettings(7L, 1L,
                new HouseUpdateRequest("새 이름", null, null, null)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_OWNER));
    }

    @Test
    void 없는_집은_404() {
        when(houseRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> houseCommandService.updateSettings(7L, 99L,
                new HouseUpdateRequest("새 이름", null, null, null)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_집은_404() {
        House deleted = mock(House.class);
        when(deleted.isDeleted()).thenReturn(true);
        when(houseRepository.findById(1L)).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> houseCommandService.updateSettings(7L, 1L,
                new HouseUpdateRequest("새 이름", null, null, null)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }
}
