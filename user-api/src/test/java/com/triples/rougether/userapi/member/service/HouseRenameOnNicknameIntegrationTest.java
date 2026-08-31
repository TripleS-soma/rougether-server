package com.triples.rougether.userapi.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.member.dto.MemberUpdateRequest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 닉네임 저장 시 기본 이름("나의 집") 그대로인 소유 집 개명(#350).
// 기본 집은 가입 시점(닉네임 없음)에 생성되므로 이름 확정은 닉네임 저장이 담당한다.
@SpringBootTest
@Transactional
class HouseRenameOnNicknameIntegrationTest {

    @Autowired private MemberService memberService;
    @Autowired private HouseCommandService houseCommandService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;

    @Test
    void 닉네임을_저장하면_기본_이름_그대로인_소유_집이_닉네임의_집으로_바뀐다() {
        User user = userRepository.save(User.signUp("rename@rougether.dev"));
        House starter = houseCommandService.createStarterHouse(user);
        assertThat(starter.getName()).isEqualTo("나의 집");
        assertThat(starter.isPublic()).isFalse(); // 기본 집은 비공개 생성

        memberService.updateMe(user.getId(), new MemberUpdateRequest("루티니", null));

        House renamed = houseRepository.findById(starter.getId()).orElseThrow();
        assertThat(renamed.getName()).isEqualTo("루티니의 집");
        assertThat(renamed.isPublic()).isFalse(); // 개명은 공개 여부를 건드리지 않음
    }

    @Test
    void 직접_지은_집_이름과_이미_개명된_집은_닉네임_변경에_따라가지_않는다() {
        User user = userRepository.save(User.signUp("rename2@rougether.dev"));
        House starter = houseCommandService.createStarterHouse(user);
        House custom = houseRepository.save(House.create(user, "내가 지은 집", null, null, 4,
                "RENAME01", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(custom, user, HouseMemberRole.OWNER));

        memberService.updateMe(user.getId(), new MemberUpdateRequest("첫닉", null));
        assertThat(houseRepository.findById(starter.getId()).orElseThrow().getName()).isEqualTo("첫닉의 집");
        assertThat(houseRepository.findById(custom.getId()).orElseThrow().getName()).isEqualTo("내가 지은 집");

        // 한 번 개명되면 기본 이름이 아니게 되어 이후 닉네임 변경엔 따라가지 않는다
        memberService.updateMe(user.getId(), new MemberUpdateRequest("새닉", null));
        assertThat(houseRepository.findById(starter.getId()).orElseThrow().getName()).isEqualTo("첫닉의 집");
    }

    @Test
    void 멤버로_참여한_남의_기본_이름_집은_바뀌지_않는다() {
        User owner = userRepository.save(User.signUp("rename-owner@rougether.dev"));
        House othersStarter = houseCommandService.createStarterHouse(owner);
        User me = userRepository.save(User.signUp("rename-member@rougether.dev"));
        houseMemberRepository.save(HouseMember.create(othersStarter, me, HouseMemberRole.MEMBER));

        memberService.updateMe(me.getId(), new MemberUpdateRequest("멤버닉", null));

        assertThat(houseRepository.findById(othersStarter.getId()).orElseThrow().getName()).isEqualTo("나의 집");
    }
}
