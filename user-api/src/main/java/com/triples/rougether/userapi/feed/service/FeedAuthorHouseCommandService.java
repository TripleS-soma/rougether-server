package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.userapi.feed.error.FeedErrorCode;
import com.triples.rougether.userapi.house.dto.HouseJoinResponse;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import com.triples.rougether.userapi.house.service.HouseJoinService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class FeedAuthorHouseCommandService {
    private final FeedAccess access;
    private final FeedPostRepository posts;
    private final HouseRepository houses;
    private final HouseMemberRepository members;
    private final HouseJoinService joins;

    public HouseJoinResponse join(Long viewer, Long postId, Long houseId) {
        // 기존 가입 경로와 같은 집 → 사용자 잠금 순서를 유지하고, 집 정원 판정과 입주를 직렬화함.
        var house = houses.findWithLockById(houseId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        access.lockActive(viewer);
        var post = posts.findVisible(postId, viewer)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_POST_NOT_FOUND));
        // 임의의 비공개 집 ID를 보내는 우회를 막고, 목록 조회 후 작성자가 나간 경우도 current read로 막음.
        members.findWithLockByHouseIdAndUserId(houseId, post.getAuthor().getId())
                .filter(HouseMember::isActive)
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        return joins.joinFromFeed(house, viewer);
    }
}
