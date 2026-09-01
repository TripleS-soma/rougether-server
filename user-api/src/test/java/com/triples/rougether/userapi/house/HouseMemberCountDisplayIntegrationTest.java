package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.HouseListResponse.HouseSummary;
import com.triples.rougether.userapi.house.dto.HouseUpdateRequest;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 구성원 수 표기의 동거 봇 제외(#352). 내부 좌석(current_member_count)은 봇 포함 그대로 두고
// 사용자향 응답의 currentMemberCount 만 실사용자 수로 내려간다 - "3/4"로 보여 참여를 망설이게 하지 않기.
@SpringBootTest
@Transactional
class HouseMemberCountDisplayIntegrationTest {

    @Autowired private HouseCommandService houseCommandService;
    @Autowired private HouseQueryService houseQueryService;
    @Autowired private HouseJoinService houseJoinService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void 봇이_입주한_기본_집은_상세_내_집_미리보기_초대코드_모두_실사용자_수만_표기한다() {
        userRepository.save(User.bot("count-bot-1", "봇1", null));
        userRepository.save(User.bot("count-bot-2", "봇2", null));
        User owner = userRepository.save(User.signUp("count@rougether.dev"));
        House starter = houseCommandService.createStarterHouse(owner);
        assertThat(starter.getCurrentMemberCount()).isEqualTo(3); // 내부 좌석은 봇 포함 유지(밀어내기 로직 불변)

        assertThat(houseQueryService.getHouseDetail(owner.getId(), starter.getId())
                .currentMemberCount()).isEqualTo(1);
        assertThat(houseQueryService.getMyHouses(owner.getId()).items().getFirst()
                .currentMemberCount()).isEqualTo(1);
        // 비공개 집 미리보기는 구성원 본인만 가능 - 그 경로로 확인
        assertThat(houseQueryService.getPreview(owner.getId(), starter.getId())
                .currentMemberCount()).isEqualTo(1);
        assertThat(houseJoinService.preview(starter.getInviteCode())
                .currentMemberCount()).isEqualTo(1);
    }

    @Test
    void 공개_전환한_봇_집은_탐색에서도_실사용자_수로_보인다() {
        userRepository.save(User.bot("count-bot-3", "봇3", null));
        User owner = userRepository.save(User.signUp("count-explore@rougether.dev"));
        House starter = houseCommandService.createStarterHouse(owner);
        houseCommandService.updateSettings(owner.getId(), starter.getId(),
                new HouseUpdateRequest(null, null, null, null, true));

        User viewer = userRepository.save(User.signUp("count-viewer@rougether.dev"));
        // 탐색은 최신 생성순이라 방금 만든 집이 첫 페이지에 있다
        HouseSummary summary = houseQueryService.explore(viewer.getId(), 0, 50, null, false)
                .items().stream()
                .filter(item -> item.houseId().equals(starter.getId()))
                .findFirst().orElseThrow();
        assertThat(summary.currentMemberCount()).isEqualTo(1);
    }

    @Test
    void 사람만_있는_집은_기존과_같이_사람_수를_표기한다() {
        User owner = userRepository.save(User.signUp("count-human@rougether.dev"));
        House house = houseRepository.save(House.create(owner, "사람 집", null, null, 4,
                "CNT00001", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        User friend = userRepository.save(User.signUp("count-friend@rougether.dev"));
        houseMemberRepository.save(HouseMember.create(house, friend, HouseMemberRole.MEMBER));
        house.increaseMemberCount();

        assertThat(houseQueryService.getHouseDetail(owner.getId(), house.getId())
                .currentMemberCount()).isEqualTo(2);
        assertThat(houseQueryService.getMyHouses(friend.getId()).items().getFirst()
                .currentMemberCount()).isEqualTo(2);
    }
}
