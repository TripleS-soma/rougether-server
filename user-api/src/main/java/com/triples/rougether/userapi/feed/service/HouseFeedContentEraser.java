package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.domain.feed.repository.FeedCommentRepository;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 집을 나가는 구성원이 "내 글도 지우기"를 고르면 그 집에 남긴 글·댓글을 지움.
// 나간 뒤에는 본인도 집 글에 접근할 수 없어 직접 지울 수 없으므로 나가는 시점에 한 번 고르게 함.
@Slf4j
@Component
@RequiredArgsConstructor
public class HouseFeedContentEraser {
    private final FeedPostRepository posts;
    private final FeedCommentRepository comments;
    private final Clock clock;

    // 호출자의 트랜잭션에서 실행함. bulk update 라 이 뒤에 같은 트랜잭션에서 피드 엔티티를 읽지 말 것.
    public void eraseAuthored(Long houseId, Long userId) {
        Instant now = clock.instant();
        int erasedComments = comments.eraseAuthoredInHouse(houseId, userId, now);
        int erasedPosts = posts.eraseAuthoredInHouse(houseId, userId, now);
        log.info("house feed content erased on leave - houseId={}, userId={}, posts={}, comments={}",
                houseId, userId, erasedPosts, erasedComments);
    }
}
