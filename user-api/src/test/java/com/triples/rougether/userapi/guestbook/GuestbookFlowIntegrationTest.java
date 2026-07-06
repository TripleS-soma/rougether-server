package com.triples.rougether.userapi.guestbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.room.repository.RoomGuestbookRepository;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateRequest;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateResponse;
import com.triples.rougether.userapi.guestbook.dto.GuestbookListResponse;
import com.triples.rougether.userapi.guestbook.service.GuestbookService;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

// 방명록 작성→커서 조회 흐름 회귀. 커서 페이징이 새 글 유입에도 흔들리지 않는지까지 실제 DB(H2/MySQL)로 검증.
@SpringBootTest
@Transactional
class GuestbookFlowIntegrationTest {

    @Autowired private GuestbookService guestbookService;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;
    @Autowired private RoomGuestbookRepository roomGuestbookRepository;

    private record Fixture(User owner, User visitor, House house) {
    }

    private Fixture fixture() {
        User owner = userRepository.save(User.signUp("gb-owner@rougether.dev"));
        User visitor = userRepository.save(User.signUp("gb-visitor@rougether.dev"));
        House house = houseRepository.save(House.create(
                owner, "방명록 하우스", null, null, 4, "GBOK2345", Instant.now().plus(Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
        houseMemberRepository.save(HouseMember.create(house, visitor, HouseMemberRole.MEMBER));
        house.increaseMemberCount();
        return new Fixture(owner, visitor, house);
    }

    private GuestbookCreateResponse write(Fixture f, User author, String content) {
        return guestbookService.write(author.getId(), f.owner().getId(),
                new GuestbookCreateRequest(f.house().getId(), content));
    }

    @Test
    void 작성하고_최신순으로_조회한다() {
        Fixture f = fixture();
        write(f, f.visitor(), "첫 번째 글");
        write(f, f.owner(), "주인도 씀");

        GuestbookListResponse response = guestbookService.getGuestbooks(
                f.visitor().getId(), f.owner().getId(), f.house().getId(), null, 20);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).content()).isEqualTo("주인도 씀");
        assertThat(response.items().get(1).content()).isEqualTo("첫 번째 글");
        assertThat(response.items().get(1).authorNickname()).isEqualTo(f.visitor().getNickname());
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void 커서_페이징은_스크롤_도중_새_글이_와도_중복_누락이_없다() {
        Fixture f = fixture();
        for (int i = 1; i <= 5; i++) {
            write(f, f.visitor(), "글 " + i);
        }

        GuestbookListResponse first = guestbookService.getGuestbooks(
                f.visitor().getId(), f.owner().getId(), f.house().getId(), null, 2);
        assertThat(first.items()).extracting("content").containsExactly("글 5", "글 4");
        assertThat(first.hasNext()).isTrue();

        // 스크롤 도중 새 글 등록 — offset 페이징이면 다음 페이지가 밀려 "글 4" 가 중복됐을 상황
        write(f, f.owner(), "새 글");

        GuestbookListResponse second = guestbookService.getGuestbooks(
                f.visitor().getId(), f.owner().getId(), f.house().getId(), first.nextCursor(), 2);
        assertThat(second.items()).extracting("content").containsExactly("글 3", "글 2");
        assertThat(second.hasNext()).isTrue();

        GuestbookListResponse third = guestbookService.getGuestbooks(
                f.visitor().getId(), f.owner().getId(), f.house().getId(), second.nextCursor(), 2);
        assertThat(third.items()).extracting("content").containsExactly("글 1");
        assertThat(third.hasNext()).isFalse();
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void 같은_집_구성원이_아니면_조회도_작성도_403() {
        Fixture f = fixture();
        User stranger = userRepository.save(User.signUp("gb-stranger@rougether.dev"));

        assertThatThrownBy(() -> guestbookService.getGuestbooks(
                stranger.getId(), f.owner().getId(), f.house().getId(), null, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
        assertThatThrownBy(() -> guestbookService.write(stranger.getId(), f.owner().getId(),
                new GuestbookCreateRequest(f.house().getId(), "몰래 씀")))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 방_주인이_그_집_구성원이_아니면_403() {
        Fixture f = fixture();
        User outsideOwner = userRepository.save(User.signUp("gb-outside@rougether.dev"));

        assertThatThrownBy(() -> guestbookService.getGuestbooks(
                f.visitor().getId(), outsideOwner.getId(), f.house().getId(), null, 20))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_MEMBER));
    }

    @Test
    void 없는_집이면_404() {
        Fixture f = fixture();

        assertThatThrownBy(() -> guestbookService.getGuestbooks(
                f.visitor().getId(), f.owner().getId(), 999999L, null, 20))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NOT_FOUND));
    }

    @Test
    void 삭제된_글은_목록에서_빠진다() {
        Fixture f = fixture();
        GuestbookCreateResponse written = write(f, f.visitor(), "지워질 글");
        write(f, f.visitor(), "남는 글");

        roomGuestbookRepository.findById(written.guestbookId()).orElseThrow();
        // soft delete 컬럼 직접 세팅 (삭제 API 는 후속 — 조회 필터만 검증)
        org.springframework.test.util.ReflectionTestUtils.setField(
                roomGuestbookRepository.findById(written.guestbookId()).orElseThrow(),
                "deletedAt", Instant.now());

        GuestbookListResponse response = guestbookService.getGuestbooks(
                f.visitor().getId(), f.owner().getId(), f.house().getId(), null, 20);
        assertThat(response.items()).extracting("content").containsExactly("남는 글");
    }
}
