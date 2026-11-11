package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.house.entity.HouseJoinRequest;
import com.triples.rougether.domain.house.entity.HouseJoinRequestStatus;
import com.triples.rougether.domain.house.entity.HouseMember;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseJoinRequestRepository;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.userapi.feed.dto.FeedAuthorHouseListResponse;
import com.triples.rougether.userapi.feed.dto.FeedAuthorHouseListResponse.HouseSummary;
import com.triples.rougether.userapi.feed.error.FeedErrorCode;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FeedAuthorHouseQueryService {
    private final FeedAccess access;
    private final FeedPostRepository posts;
    private final HouseMemberRepository members;
    private final HouseJoinRequestRepository requests;

    public FeedAuthorHouseListResponse list(Long viewer, Long postId) {
        access.active(viewer);
        var post = posts.findVisible(postId, viewer)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_POST_NOT_FOUND));
        var authorHouses = members.findFeedAuthorHousesByUserIdAndStatus(
                post.getAuthor().getId(), HouseMemberStatus.ACTIVE);
        if (authorHouses.isEmpty()) return new FeedAuthorHouseListResponse(List.of());

        // 집 수와 관계없이 요청자 상태와 실사용자 수를 일괄 조회함.
        var houseIds = authorHouses.stream().map(member -> member.getHouse().getId()).toList();
        var myStatuses = members.findByHouseIdInAndUserId(houseIds, viewer).stream()
                .collect(Collectors.toMap(member -> member.getHouse().getId(), HouseMember::getStatus));
        var requestStatuses = requests.findByHouseIdInAndUserId(houseIds, viewer).stream()
                .filter(request -> request.getStatus() != HouseJoinRequestStatus.ACCEPTED)
                .collect(Collectors.toMap(request -> request.getHouse().getId(), HouseJoinRequest::getStatus));
        var humanCounts = members.countActiveHumansByHouseIds(houseIds, HouseMemberStatus.ACTIVE).stream()
                .collect(Collectors.toMap(HouseMemberRepository.HouseHumanCount::getHouseId,
                        HouseMemberRepository.HouseHumanCount::getHumanCount));
        return new FeedAuthorHouseListResponse(authorHouses.stream().map(member -> {
            var house = member.getHouse();
            var myStatus = myStatuses.get(house.getId());
            boolean isMember = myStatus == HouseMemberStatus.ACTIVE;
            var requestStatus = isMember ? null : requestStatuses.get(house.getId());
            int humanCount = Math.toIntExact(humanCounts.getOrDefault(house.getId(), 0L));
            // 동거 봇이 차지한 자리는 신청 수락 때 양보하므로 실사용자 수로 정원을 표시함.
            boolean isFull = house.getMaxMembers() != null && humanCount >= house.getMaxMembers();
            boolean requiresApproval = !house.isOnboardingAutoJoinEnabled();
            boolean canJoin = !isMember && !isFull && myStatus != HouseMemberStatus.KICKED
                    && (!requiresApproval || requestStatus != HouseJoinRequestStatus.PENDING);
            return new HouseSummary(house.getId(), house.getName(), house.getCoverImageKey(), house.getLevel(),
                    house.getDisplayMode(), house.isPublic(), requiresApproval, humanCount, house.getMaxMembers(), isMember, isFull,
                    myStatus, requestStatus, canJoin);
        }).toList());
    }
}
