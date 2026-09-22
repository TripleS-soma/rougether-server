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
@Table(name = "feed_images")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedImage extends BaseCreatedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false) private User owner;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "post_id") private FeedPost post;
    @Column(name = "storage_key", nullable = false) private String storageKey;
    @Column(nullable = false) private int width;
    @Column(nullable = false) private int height;
    @Column(nullable = false) private boolean ready;
    @Column(name = "sort_order") private Integer sortOrder;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;

    public static FeedImage reserve(User owner, String key, int width, int height, Instant expiresAt) {
        FeedImage image = new FeedImage();
        image.owner = owner; image.storageKey = key; image.width = width; image.height = height; image.expiresAt = expiresAt;
        return image;
    }
    public void expire(Instant now) { expiresAt = now; }
    public void complete() { ready = true; }
    public void attach(FeedPost post, int order) { this.post = post; this.sortOrder = order; }
}
