package com.triples.rougether.userapi.moderation.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.chat.repository.ChatMessageRepository;
import com.triples.rougether.domain.feed.entity.FeedComment;
import com.triples.rougether.domain.feed.entity.FeedPost;
import com.triples.rougether.domain.feed.repository.FeedCommentRepository;
import com.triples.rougether.domain.feed.repository.FeedPostRepository;
import com.triples.rougether.domain.market.entity.MarketAsset;
import com.triples.rougether.domain.market.repository.MarketAssetRepository;
import com.triples.rougether.domain.moderation.entity.ContentReport;
import com.triples.rougether.domain.moderation.entity.ContentReportTargetType;
import com.triples.rougether.domain.moderation.repository.ContentReportRepository;
import com.triples.rougether.userapi.chat.error.ChatErrorCode;
import com.triples.rougether.userapi.chat.service.ChatAccessPolicy;
import com.triples.rougether.userapi.feed.error.FeedErrorCode;
import com.triples.rougether.userapi.feed.service.FeedAccess;
import com.triples.rougether.userapi.market.error.MarketErrorCode;
import com.triples.rougether.userapi.moderation.dto.ContentReportRequest;
import com.triples.rougether.userapi.moderation.dto.ContentReportResponse;
import com.triples.rougether.userapi.moderation.error.ModerationErrorCode;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 콘텐츠 신고 접수(#399). 사용자·대상당 1건 — 재신고는 기존 신고를 돌려줌(자동 숨김 없음, 운영자 대기열로 감).
// 차단한 상대의 콘텐츠도 신고할 수 있게 존재 여부만 보고 차단 필터는 적용하지 않음.
@Service
@RequiredArgsConstructor
@Transactional
public class ContentReportService {

    private final FeedAccess access;
    private final ChatAccessPolicy chatAccess;
    private final ChatMessageRepository chatMessages;
    private final FeedPostRepository posts;
    private final FeedCommentRepository comments;
    private final MarketAssetRepository assets;
    private final ContentReportRepository reports;
    private final Clock clock;

    public ContentReportResponse reportFeedPost(Long userId, Long postId, ContentReportRequest request) {
        access.lockActive(userId);
        FeedPost post = existingPost(postId, userId);
        return receive(userId, ContentReportTargetType.FEED_POST, postId, post.getAuthor().getId(), request);
    }

    public ContentReportResponse reportFeedComment(Long userId, Long postId, Long commentId,
                                                   ContentReportRequest request) {
        access.lockActive(userId);
        existingPost(postId, userId);
        FeedComment comment = comments.findByIdAndPostId(commentId, postId)
                .filter(found -> found.getDeletedAt() == null && !found.getAuthor().isDeleted())
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_COMMENT_NOT_FOUND));
        return receive(userId, ContentReportTargetType.FEED_COMMENT, commentId, comment.getAuthor().getId(), request);
    }

    // 거래 정지된 종목도 신고할 수 있음. 제작자가 탈퇴했으면 소유자 스냅샷은 null.
    public ContentReportResponse reportMarketAsset(Long userId, Long assetId, ContentReportRequest request) {
        access.lockActive(userId);
        MarketAsset asset = assets.findById(assetId)
                .orElseThrow(() -> new BusinessException(MarketErrorCode.ASSET_NOT_FOUND));
        return receive(userId, ContentReportTargetType.MARKET_ASSET, assetId, asset.getCreatorUserId(), request);
    }

    // 그 방의 현재 구성원만 신고할 수 있음. lockWriter가 신고자 회원 행을 잠금.
    // lockWriter의 첫 조회가 잠금 없는 조회라 READ COMMITTED여야 잠금 뒤에 앞선 신고가 보임.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ContentReportResponse reportChatMessage(Long userId, Long roomId, Long messageId,
                                                   ContentReportRequest request) {
        chatAccess.lockWriter(roomId, userId);
        var message = chatMessages.findById(messageId)
                .filter(found -> found.getRoomId().equals(roomId) && !found.isDeleted())
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHAT_MESSAGE_NOT_FOUND));
        return receive(userId, ContentReportTargetType.CHAT_MESSAGE, messageId, message.getSender().getId(), request);
    }

    // 신고자 회원 행을 잠근 뒤 호출됨 — 같은 신고자의 동시 재신고가 여기서 직렬화돼 unique 충돌로 새지 않음.
    private ContentReportResponse receive(Long userId, ContentReportTargetType type, Long targetId, Long ownerId,
                                          ContentReportRequest request) {
        if (userId.equals(ownerId)) {
            throw new BusinessException(ModerationErrorCode.REPORT_SELF_TARGET);
        }
        ContentReport report = reports.findByReporterUserIdAndTargetTypeAndTargetId(userId, type, targetId)
                .orElseGet(() -> reports.save(ContentReport.receive(userId, type, targetId, ownerId,
                        request.reason(), normalize(request.detail()), clock.instant())));
        return ContentReportResponse.of(report);
    }

    // 집 글과 그 댓글은 그 집 구성원만 신고할 수 있고, 비구성원에게는 없는 글로 응답함(#528).
    private FeedPost existingPost(Long postId, Long userId) {
        return posts.findById(postId)
                .filter(post -> post.getDeletedAt() == null && !post.getAuthor().isDeleted()
                        && access.canSee(post, userId))
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_POST_NOT_FOUND));
    }

    private static String normalize(String detail) {
        if (detail == null) {
            return null;
        }
        String value = detail.strip();
        if (value.length() > ContentReport.DETAIL_MAX_LENGTH) {
            value = value.substring(0, ContentReport.DETAIL_MAX_LENGTH);
        }
        return value.isEmpty() ? null : value;
    }
}
