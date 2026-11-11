package com.triples.rougether.userapi.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.FeedBoardType;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.house.entity.*;
import com.triples.rougether.domain.house.repository.*;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.feed.service.FeedAuthorHouseCommandService;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class FeedAuthorHouseConcurrencyTest {
    @Autowired FeedAuthorHouseCommandService commands;
    @Autowired UserRepository users;
    @Autowired FeedPostRepository posts;
    @Autowired HouseRepository houses;
    @Autowired HouseMemberRepository members;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @Test void 비공개_집의_마지막_한_자리에_동시에_입주해도_한_명만_가입한다() throws Exception {
        var userIds = new ArrayList<Long>();
        Long[] fixture = tx.execute(s -> {
            var author = users.save(User.signUp());
            userIds.add(author.getId());
            userIds.add(users.save(User.signUp()).getId());
            userIds.add(users.save(User.signUp()).getId());
            var house = houses.save(House.createPrivate(author, "마지막 한 자리", null, null, 2, UUID.randomUUID().toString(), Instant.now()));
            house.changeOnboardingAutoJoinEnabled(true);
            members.save(HouseMember.create(house, author, HouseMemberRole.OWNER));
            var post = posts.save(FeedPost.create(author, UUID.randomUUID().toString(), "a".repeat(64), "함께 살아요", FeedBoardType.FREE));
            return new Long[]{house.getId(), post.getId()};
        });
        Long houseId = fixture[0], postId = fixture[1];
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (Long applicant : userIds.subList(1, 3)) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        var result = commands.join(applicant, postId, houseId);
                        assertThat(result.status()).isEqualTo(HouseMemberStatus.ACTIVE);
                        return "JOINED";
                    } catch (BusinessException e) {
                        assertThat(e.getErrorCode()).isEqualTo(HouseErrorCode.HOUSE_FULL);
                        return "FULL";
                    }
                }));
            }
            start.countDown();
            assertThat(List.of(results.get(0).get(30, TimeUnit.SECONDS), results.get(1).get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("JOINED", "FULL");
            assertThat(members.countByHouseIdAndStatus(houseId, HouseMemberStatus.ACTIVE)).isEqualTo(2);
            assertThat(houses.findById(houseId).orElseThrow().getCurrentMemberCount()).isEqualTo(2);
            assertThat(jdbc.queryForObject("select count(*) from notification where user_id=? and type='HOUSE_MEMBER_JOINED'",
                    Long.class, userIds.getFirst())).isEqualTo(1L);
        } finally {
            tx.executeWithoutResult(s -> {
                userIds.forEach(id -> jdbc.update("delete from notification where user_id=?", id));
                jdbc.update("delete from house_members where house_id=?", houseId);
                jdbc.update("delete from house where id=?", houseId);
                jdbc.update("delete from feed_posts where id=?", postId);
                userIds.forEach(users::deleteById);
            });
        }
    }
}
