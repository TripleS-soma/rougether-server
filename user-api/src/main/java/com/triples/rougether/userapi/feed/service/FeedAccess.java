package com.triples.rougether.userapi.feed.service;

import com.triples.rougether.common.error.AuthErrorCode;
import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.feed.entity.FeedComment;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedCommentRepository;
import com.triples.rougether.domain.house.entity.HouseMemberStatus;
import com.triples.rougether.domain.house.repository.HouseMemberRepository;
import com.triples.rougether.domain.house.repository.HouseRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.moderation.repository.UserBlockRepository;
import com.triples.rougether.userapi.feed.error.FeedErrorCode;
import com.triples.rougether.userapi.house.error.HouseErrorCode;
import java.util.stream.Stream;
import com.triples.rougether.domain.member.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FeedAccess {
    private final UserRepository users;
    private final FeedCommentRepository comments;
    private final UserBlockRepository blocks;
    private final HouseRepository houses;
    private final HouseMemberRepository members;

    public User active(Long userId) {
        return requireActive(users.findById(userId).orElse(null));
    }
    // 쓰기는 회원 → 게시물 → 이미지 순서로 잠금. 탈퇴의 회원 잠금과 직렬화함.
    public User lockActive(Long userId) {
        return requireActive(users.findByIdForUpdate(userId).orElse(null));
    }

    public User lockCommentParticipants(Long actorId, Long authorId, Long replyAuthorId) {
        User actor = null;
        // 게시물·답글 수신자의 알림 FK도 회원을 참조하므로 모든 참여자를 정렬해 잠금.
        // 답글 대상의 탈퇴 여부는 재시도 판정 뒤에 검사해 이미 성공한 요청의 결과를 유지함.
        for (Long id : Stream.of(actorId, authorId, replyAuthorId)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList()) {
            User locked = users.findByIdForUpdate(id).orElse(null);
            if (id.equals(actorId)) actor = requireActive(locked);
            else if (id.equals(authorId) && (locked == null || locked.isDeleted() || locked.isBot()))
                throw new BusinessException(FeedErrorCode.FEED_POST_NOT_FOUND);
        }
        return actor;
    }
    // 삭제·탈퇴한 원댓글 아래의 살아 있는 답글은 계속 접근할 수 있음.
    public void requireThreadVisible(Long viewer, Long postId, Long parentId) {
        FeedComment parent = comments.findByIdAndPostId(parentId, postId)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_COMMENT_NOT_FOUND));
        if (parent.getParentCommentId() != null
                || blocks.existsByBlockerUserIdAndBlockedUserId(viewer, parent.getAuthor().getId()))
            throw new BusinessException(FeedErrorCode.FEED_COMMENT_NOT_FOUND);
    }

    // 집을 직접 지정한 요청(등록·목록)은 집 존재와 구성원 여부를 구분해 알림(#528).
    public void requireHouseParticipant(Long houseId, Long userId) {
        houses.findById(houseId).filter(house -> !house.isDeleted())
                .orElseThrow(() -> new BusinessException(HouseErrorCode.HOUSE_NOT_FOUND));
        if (!isHouseParticipant(houseId, userId)) throw new BusinessException(HouseErrorCode.HOUSE_NOT_MEMBER);
    }
    // 삭제되지 않은 집의 ACTIVE 구성원(탈퇴·봇·AI 계정 제외)만 집 피드에 참여함.
    // 집·구성원 행은 잠그지 않음 — 집 참여 흐름(집 → 회원 잠금)과 순서가 뒤집혀 교착하는 것을 피함.
    // 그래서 검사 직후 강퇴된 구성원의 글이 한 건 남을 수 있으나 본인 글일 뿐 비구성원 노출은 없음.
    public boolean isHouseParticipant(Long houseId, Long userId) {
        return members.existsFeedParticipant(houseId, userId, HouseMemberStatus.ACTIVE);
    }
    // 집 글과 그 댓글은 그 집의 현재 방장도 지울 수 있음(#529). 공개 글에는 방장 권한이 없음.
    public boolean isModerator(FeedPost post, Long userId) {
        return post.isHousePost() && members.existsFeedModerator(post.getHouseId(), userId, HouseMemberStatus.ACTIVE);
    }
    public boolean canSee(FeedPost post, Long userId) {
        return !post.isHousePost() || isHouseParticipant(post.getHouseId(), userId);
    }
    // 글 ID로 접근하는 경로는 집 글의 존재 자체를 비구성원에게 숨김.
    public void requireVisible(FeedPost post, Long userId) {
        if (!canSee(post, userId)) throw new BusinessException(FeedErrorCode.FEED_POST_NOT_FOUND);
    }

    private User requireActive(User user) {
        if (user == null || user.isDeleted() || user.isBot()) throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        return user;
    }
}
