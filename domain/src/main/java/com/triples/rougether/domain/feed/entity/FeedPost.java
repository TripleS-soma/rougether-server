package com.triples.rougether.domain.feed.entity;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.support.BaseEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
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
    @Enumerated(EnumType.STRING)
    @Column(name = "board_type", nullable = false, length = 20) private FeedBoardType boardType;
    @Column(nullable = false, length = 2000) private String content;
    @Column(name = "deleted_at") private Instant deletedAt;
    // 인증글이 연결한 루틴 완료 기록. 제목은 연결 시점 스냅샷이라 루틴 이름 변경·삭제와 무관하게 유지함
    @Column(name = "routine_id") private Long routineId;
    @Column(name = "routine_date") private LocalDate routineDate;
    @Column(name = "routine_title", length = 160) private String routineTitle;

    public static FeedPost create(User author, String clientPostId, String hash, String content) {
        return create(author, clientPostId, hash, content, FeedBoardType.VERIFICATION);
    }
    public static FeedPost create(User author, String clientPostId, String hash, String content, FeedBoardType boardType) {
        FeedPost post = new FeedPost();
        post.author = author; post.clientPostId = clientPostId; post.requestHash = hash; post.content = content; post.boardType = boardType;
        return post;
    }
    public void updateContent(String content) { this.content = content; }
    public void changeBoard(FeedBoardType boardType) { this.boardType = boardType; }
    public void linkRoutine(Long routineId, LocalDate routineDate, String routineTitle) {
        this.routineId = routineId; this.routineDate = routineDate; this.routineTitle = routineTitle;
    }
    public void clearRoutine() { linkRoutine(null, null, null); }
    public boolean hasRoutine() { return routineId != null; }
    public void delete(Instant now) { if (deletedAt == null) { deletedAt = now; content = ""; clearRoutine(); } }
}
