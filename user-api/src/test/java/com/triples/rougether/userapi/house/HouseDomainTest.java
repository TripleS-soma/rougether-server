package com.triples.rougether.userapi.house;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.member.entity.User;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

// House 참여 판정 도메인 메서드 (isFull / isInviteExpired / increaseMemberCount) 실제 로직 검증.
class HouseDomainTest {

    private House house(Integer maxMembers, Instant inviteExpiresAt) {
        return House.create(mock(User.class), "테스트 하우스", null, null, maxMembers, "ABCD2345", inviteExpiresAt);
    }

    @Test
    void 정원은_max_members_도달_시_가득_찬_것으로_본다() {
        House house = house(2, Instant.now().plus(Duration.ofDays(7)));
        assertThat(house.isFull()).isFalse(); // 1/2

        house.increaseMemberCount();
        assertThat(house.getCurrentMemberCount()).isEqualTo(2);
        assertThat(house.isFull()).isTrue(); // 2/2
    }

    @Test
    void max_members가_null이면_무제한이다() {
        House house = house(null, Instant.now().plus(Duration.ofDays(7)));
        for (int i = 0; i < 100; i++) {
            house.increaseMemberCount();
        }
        assertThat(house.isFull()).isFalse();
    }

    @Test
    void 만료_시각이_지났거나_없으면_만료다() {
        assertThat(house(4, Instant.now().minusSeconds(1)).isInviteExpired()).isTrue();
        assertThat(house(4, null).isInviteExpired()).isTrue();
        assertThat(house(4, Instant.now().plusSeconds(60)).isInviteExpired()).isFalse();
    }
}
