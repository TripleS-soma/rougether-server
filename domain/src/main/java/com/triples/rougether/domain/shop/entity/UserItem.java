package com.triples.rougether.domain.shop.entity;

import com.triples.rougether.domain.member.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_items")
public class UserItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private UserItem(User user, Item item) {
        this.user = user;
        this.item = item;
        this.acquiredAt = Instant.now();
    }

    // 아이템 지급(뽑기/구매).
    public static UserItem create(User user, Item item) {
        return new UserItem(user, item);
    }

    // 거래소 판매 등록으로 맡김. 인벤토리 조회(deletedAt is null)에서 빠짐.
    public void deactivate(Instant now) {
        if (deletedAt != null) {
            throw new IllegalStateException("already inactive user item: " + id);
        }
        this.deletedAt = now;
    }

    // 판매 취소·만료로 돌려받음. 활성 유니크(uq_user_items_active)가 같은 아이템 중복 활성을 막음.
    public void reactivate() {
        if (deletedAt == null) {
            throw new IllegalStateException("already active user item: " + id);
        }
        this.deletedAt = null;
    }
}
