package com.triples.rougether.domain.feed.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "feed_posts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedPost extends BaseEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false) private User author;
    @Column(name = "client_post_id", nullable = false, length = 36) private String clientPostId;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(nullable = false, length = 2000) private String content;
    @Column(name = "deleted_at") private Instant deletedAt;

    public static FeedPost create(User author, String clientPostId, String hash, String content) {
        FeedPost post = new FeedPost();
        post.author = author; post.clientPostId = clientPostId; post.requestHash = hash; post.content = content;
        return post;
    }
    public void updateContent(String content) { this.content = content; }
    public void delete(Instant now) { if (deletedAt == null) { deletedAt = now; content = ""; } }
}
