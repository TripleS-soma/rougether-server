package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.triples.rougether.domain.feed.entity.FeedBoardType;
import com.triples.rougether.domain.house.entity.House;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberRole;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.feed.dto.FeedCreateRequest;
import com.triples.rougether.userapi.feed.service.FeedCommandService;
import com.triples.rougether.userapi.feed.service.FeedQueryService;
import com.triples.rougether.userapi.notification.fcm.FcmPushExecutor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

// 집 피드 새 글 푸시(#530)가 실제 커밋 뒤 비동기로 나가는지 확인함. 수신자 계산은 HouseFeedNotificationIntegrationTest 가 맡음.
@SpringBootTest
class HouseFeedPushAfterCommitTest {
    // 접속 선물의 독립 트랜잭션은 전용 테스트에서 검증하고, 이 도메인의 픽스처와 분리함.
    @MockitoBean com.triples.rougether.userapi.logingift.service.LoginGiftAccessRecorder loginGifts;
    @MockitoBean FcmPushExecutor push;
    @Autowired FeedCommandService commands;
    @Autowired FeedQueryService query;
    @Autowired UserRepository users;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> userIds = new ArrayList<>();
    private Long houseId;

    @AfterEach void clean() {
        for (Long id : userIds) jdbc.update("delete from feed_posts where author_id=?", id);
        if (houseId != null) {
            jdbc.update("delete from house_members where house_id=?", houseId);
            jdbc.update("delete from house where id=?", houseId);
        }
        for (Long id : userIds) {
            jdbc.update("delete from user_daily_activity where user_id=?", id);
            jdbc.update("delete from users where id=?", id);
        }
    }

    @Test void 집_글이_커밋되면_구성원에게_푸시하고_발송이_실패해도_글은_남는다() {
        Long[] ids = tx.execute(status -> {
            User owner = users.save(User.signUp());
            User member = users.save(User.signUp());
            House house = houses.save(House.createPrivate(owner, "아침을 여는 집", "소개", "house/cover.png", 6,
                    UUID.randomUUID().toString(), Instant.now().minusSeconds(1)));
            members.save(HouseMember.create(house, owner, HouseMemberRole.OWNER));
            house.increaseMemberCount();
            members.save(HouseMember.create(house, member, HouseMemberRole.MEMBER));
            return new Long[] {owner.getId(), member.getId(), house.getId()};
        });
        Long owner = ids[0], member = ids[1];
        houseId = ids[2];
        userIds.addAll(List.of(owner, member));
        doThrow(new IllegalStateException("fcm down")).when(push).pushWithoutRecord(anyLong(), anyString(), anyString(), any());

        Long post = commands.create(member, new FeedCreateRequest(UUID.randomUUID(), "커밋 뒤 알리는 글", List.of(),
                FeedBoardType.FREE, null, null, houseId));

        verify(push, timeout(5000)).pushWithoutRecord(eq(owner), eq("아침을 여는 집"), anyString(), any());
        verify(push, never()).pushWithoutRecord(eq(member), anyString(), anyString(), any());
        assertThat(query.get(owner, post).content()).isEqualTo("커밋 뒤 알리는 글");
    }
}
