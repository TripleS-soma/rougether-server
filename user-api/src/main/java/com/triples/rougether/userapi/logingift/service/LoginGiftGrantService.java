package com.triples.rougether.userapi.logingift.service;

import com.triples.rougether.domain.logingift.repository.LoginGiftParticipationRepository;
import com.triples.rougether.domain.member.entity.UserWallet;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import com.triples.rougether.domain.shared.WalletHistoryReason;
import com.triples.rougether.domain.shop.entity.UserItem;
import com.triples.rougether.domain.shop.repository.UserItemRepository;
import com.triples.rougether.userapi.wallet.service.WalletHistoryRecorder;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class LoginGiftGrantService {
    private final UserRepository users;
    private final UserWalletRepository wallets;
    private final UserItemRepository userItems;
    private final LoginGiftParticipationRepository participations;
    private final WalletHistoryRecorder histories;
    private final Clock kstClock;

    public boolean grant(Long eventId, Long userId) {
        // 접속 기록과 같은 순서(user → participation → wallet)로 잠가 교착과 동시 중복 지급을 방지함.
        var user = users.findByIdForUpdate(userId)
                .filter(u -> !u.isDeleted() && !u.isBot() && !u.isAi()).orElse(null);
        if (user == null) return false;
        var participation = participations.findForUpdate(eventId, userId).orElseThrow();
        if (participation.getGrantedAt() != null) return true;
        var event = participation.getEvent();
        if (!event.accepts(participation.getEligibleAt())) return false;
        var item = event.getRewardItem();
        if (!item.isActive() || !"positioned".equals(item.getPlacementType())) {
            throw new IllegalStateException("접속 선물 가구가 활성 배치형 가구가 아닙니다");
        }
        var wallet = wallets.findWithLockByUserIdAndCurrencyType(userId, CurrencyType.COIN)
                .orElseGet(() -> wallets.save(UserWallet.create(user, CurrencyType.COIN)));
        wallet.add(event.getCoinAmount());
        histories.record(wallet, event.getCoinAmount(), WalletHistoryReason.LOGIN_GIFT,
                "LOGIN_GIFT_PARTICIPATION", participation.getId());
        var reward = userItems.findByUserIdAndItemIdAndDeletedAtIsNull(userId, item.getId())
                .orElseGet(() -> userItems.save(UserItem.create(user, item)));
        participation.grant(reward, kstClock.instant());
        return true;
    }
}
