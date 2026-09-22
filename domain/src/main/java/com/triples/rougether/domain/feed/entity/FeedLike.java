package com.triples.rougether.domain.feed.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "feed_likes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedLike {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false) private FeedPost post;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private User user;
    public FeedLike(FeedPost post, User user) { this.post = post; this.user = user; }
}
