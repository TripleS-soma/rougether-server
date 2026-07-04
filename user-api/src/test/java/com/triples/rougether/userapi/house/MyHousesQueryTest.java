package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.house.dto.MyHouseListResponse;
import com.triples.rougether.userapi.house.service.HouseQueryService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

// 내 집 목록 쿼리 의미 검증 - ACTIVE만·삭제 집 제외·myRole 매핑·가입순 정렬을 실제 DB(H2)로 확인.
@SpringBootTest
@Transactional
class MyHousesQueryTest {

    @Autowired private HouseQueryService houseQueryService;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User me;
    private House ownedHouse;
    private House joinedHouse;
    private House leftHouse;
    private House deletedHouse;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.signUp("my-houses-test@rougether.dev"));
        User other = userRepository.save(User.signUp("my-houses-other@rougether.dev"));
        Instant expires = Instant.now().plus(Duration.ofDays(7));

        // 먼저 가입: 내가 만든 집(OWNER)
        ownedHouse = houseRepository.save(House.create(me, "내가 만든 집", null, null, 4, "MYHOUSE2", expires));
        HouseMember owned = houseMemberRepository.save(HouseMember.create(ownedHouse, me, HouseMemberRole.OWNER));

        // 나중 가입: 참여한 집(MEMBER) - joinedAt 차이를 보장하기 위해 직접 갱신
        joinedHouse = houseRepository.save(House.create(other, "참여한 집", null, null, 4, "MYHOUSE3", expires));
        HouseMember joined = houseMemberRepository.save(HouseMember.create(joinedHouse, me, HouseMemberRole.MEMBER));
        jdbcTemplate.update("UPDATE house_members SET joined_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(60)), joined.getId());

        // 탈퇴한 집(LEFT)
        leftHouse = houseRepository.save(House.create(other, "탈퇴한 집", null, null, 4, "MYHOUSE4", expires));
        HouseMember left = houseMemberRepository.save(HouseMember.create(leftHouse, me, HouseMemberRole.MEMBER));
        jdbcTemplate.update("UPDATE house_members SET status = 'LEFT', left_at = CURRENT_TIMESTAMP WHERE id = ?",
                left.getId());

        // 삭제된 집(membership 은 ACTIVE 지만 집이 soft delete)
        deletedHouse = houseRepository.save(House.create(other, "삭제된 집", null, null, 4, "MYHOUSE5", expires));
        houseMemberRepository.save(HouseMember.create(deletedHouse, me, HouseMemberRole.MEMBER));
        jdbcTemplate.update("UPDATE house SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedHouse.getId());
    }

    @Test
    void ACTIVE_멤버십의_삭제_안_된_집만_가입순으로_내려준다() {
        MyHouseListResponse response = houseQueryService.getMyHouses(me.getId());

        assertThat(response.items()).extracting(MyHouseListResponse.MyHouseSummary::houseId)
                .containsExactly(ownedHouse.getId(), joinedHouse.getId()); // 가입순, LEFT·삭제 집 제외
    }

    @Test
    void myRole과_집_정보가_매핑된다() {
        MyHouseListResponse response = houseQueryService.getMyHouses(me.getId());

        MyHouseListResponse.MyHouseSummary owned = response.items().get(0);
        assertThat(owned.name()).isEqualTo("내가 만든 집");
        assertThat(owned.myRole()).isEqualTo(HouseMemberRole.OWNER);
        assertThat(owned.currentMemberCount()).isEqualTo(1);
        assertThat(owned.maxMembers()).isEqualTo(4);
        assertThat(owned.level()).isZero();
        assertThat(owned.joinedAt()).isNotNull();

        MyHouseListResponse.MyHouseSummary joined = response.items().get(1);
        assertThat(joined.myRole()).isEqualTo(HouseMemberRole.MEMBER);
    }

    @Test
    void 아무_집에도_없으면_빈_목록이다() {
        User nobody = userRepository.save(User.signUp("my-houses-nobody@rougether.dev"));

        assertThat(houseQueryService.getMyHouses(nobody.getId()).items()).isEmpty();
    }
}
