package com.triples.rougether.domain.feed.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseCreatedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "feed_comments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedComment extends BaseCreatedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false) private FeedPost post;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false) private User author;
    @Column(name = "client_comment_id", nullable = false, length = 36) private String clientCommentId;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, length = 500) private String content;
    @Column(name = "deleted_at") private Instant deletedAt;
    // 화면의 들여쓰기는 한 단계로 고정하고 실제 답글 대상은 별도로 보존함.
    @Column(name = "parent_comment_id") private Long parentCommentId;
    @Column(name = "reply_to_comment_id") private Long replyToCommentId;

    public static FeedComment create(FeedPost post, User author, String clientId, String hash, String content) {
        return create(post, author, clientId, hash, content, null, null);
    }
    public static FeedComment create(FeedPost post, User author, String clientId, String hash, String content,
                                     Long parentCommentId, Long replyToCommentId) {
        FeedComment comment = new FeedComment();
        comment.post = post; comment.author = author; comment.clientCommentId = clientId;
        comment.requestHash = hash; comment.content = content;
        comment.parentCommentId = parentCommentId; comment.replyToCommentId = replyToCommentId;
        return comment;
    }
    public void delete(Instant now) { if (deletedAt == null) { deletedAt = now; content = ""; } }
}
