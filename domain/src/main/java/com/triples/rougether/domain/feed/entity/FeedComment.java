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

    public static FeedComment create(FeedPost post, User author, String clientId, String hash, String content) {
        FeedComment comment = new FeedComment();
        comment.post = post; comment.author = author; comment.clientCommentId = clientId;
        comment.requestHash = hash; comment.content = content;
        return comment;
    }
    public void delete(Instant now) { if (deletedAt == null) { deletedAt = now; content = ""; } }
}
