package com.triples.rougether.userapi.global.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.entity.HouseMissionType;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.moderation.entity.BannedWord;
import com.triples.rougether.domain.moderation.repository.BannedWordRepository;
import com.triples.rougether.userapi.guestbook.dto.GuestbookCreateRequest;
import com.triples.rougether.userapi.guestbook.service.GuestbookService;
import com.triples.rougether.userapi.house.dto.HouseCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseMissionCreateRequest;
import com.triples.rougether.userapi.house.dto.HouseUpdateRequest;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseCommandService;
import com.triples.rougether.userapi.house.service.HouseMissionService;
import com.triples.rougether.userapi.member.dto.MemberUpdateRequest;
import com.triples.rougether.userapi.member.error.MemberErrorCode;
import com.triples.rougether.userapi.member.service.MemberService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

// 금칙어 차단 (#209) - 정규화 포함 매칭으로 닉네임·집 이름 등 텍스트 입력을 400 으로 거부.
@SpringBootTest
@Transactional
class BannedWordFilterIntegrationTest {

    @Autowired private BannedWordRepository bannedWordRepository;
    @Autowired private BannedWordChecker bannedWordChecker;
    @Autowired private MemberService memberService;
    @Autowired private HouseCommandService houseCommandService;
    @Autowired private GuestbookService guestbookService;
    @Autowired private HouseMissionService houseMissionService;
    @Autowired private UserRepository userRepository;
    @Autowired private HouseRepository houseRepository;
    @Autowired private HouseMemberRepository houseMemberRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.signUp("banned-word@rougether.dev"));
        bannedWordRepository.save(BannedWord.of("시발"));
        // TTL 캐시가 이전 테스트의 목록을 물고 있지 않도록 강제 만료
        ReflectionTestUtils.setField(bannedWordChecker, "cacheLoadedAt", Instant.EPOCH);
    }

    @Test
    void 금칙어_판정은_정규화_후_부분_매칭이다() {
        assertThat(bannedWordChecker.containsBannedWord("시발")).isTrue();
        assertThat(bannedWordChecker.containsBannedWord("시@발")).isTrue();
        assertThat(bannedWordChecker.containsBannedWord("시 발 놈")).isTrue();
        assertThat(bannedWordChecker.containsBannedWord("멀쩡한닉네임")).isFalse();
        assertThat(bannedWordChecker.containsBannedWord("")).isFalse();
        assertThat(bannedWordChecker.containsBannedWord(null)).isFalse();
    }

    @Test
    void 금칙어_닉네임은_400_이고_변경되지_않는다() {
        assertThatThrownBy(() -> memberService.updateMe(user.getId(),
                new MemberUpdateRequest("시@발닉네임", null)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_NICKNAME_BANNED));
        assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname()).isNull();
    }

    @Test
    void 금칙어_bio_는_400_이다() {
        assertThatThrownBy(() -> memberService.updateMe(user.getId(),
                new MemberUpdateRequest("정상닉네임", "소개글에 시발 포함")))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MemberErrorCode.MEMBER_BIO_BANNED));
    }

    @Test
    void 금칙어_집_이름은_400_이다() {
        assertThatThrownBy(() -> houseCommandService.create(user.getId(),
                new HouseCreateRequest("시발하우스", null, null, null, List.of(1L))))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NAME_BANNED));
    }

    @Test
    void 금칙어가_없는_텍스트는_그대로_통과한다() {
        assertThat(memberService.updateMe(user.getId(),
                new MemberUpdateRequest("멀쩡닉", "멀쩡한 소개")).nickname()).isEqualTo("멀쩡닉");
    }

    @Test
    void 금칙어_집_이름_수정은_400_이다() {
        House house = createHouseWithOwner();
        assertThatThrownBy(() -> houseCommandService.updateSettings(user.getId(), house.getId(),
                new HouseUpdateRequest("시발하우스", null, null, null)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_NAME_BANNED));
    }

    @Test
    void 금칙어_방명록은_400_이다() {
        House house = createHouseWithOwner();
        assertThatThrownBy(() -> guestbookService.write(user.getId(), user.getId(),
                new GuestbookCreateRequest(house.getId(), "방명록에 시@발 포함")))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.GUESTBOOK_CONTENT_BANNED));
    }

    @Test
    void 금칙어_미션_제목은_400_이다() {
        House house = createHouseWithOwner();
        assertThatThrownBy(() -> houseMissionService.create(user.getId(), house.getId(),
                new HouseMissionCreateRequest("시발 미션", HouseMissionType.WEEKLY_MEMBER_COUNT, 10, null, null)))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(HouseErrorCode.HOUSE_MISSION_TITLE_BANNED));
    }

    private House createHouseWithOwner() {
        House house = houseRepository.save(House.create(
                user, "필터 하우스", null, null, 4, "BANWD234",
                Instant.now().plus(java.time.Duration.ofDays(7))));
        houseMemberRepository.save(HouseMember.create(house, user, HouseMemberRole.OWNER));
        return house;
    }
}
