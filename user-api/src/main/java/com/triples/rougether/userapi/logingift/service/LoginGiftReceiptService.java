package com.triples.rougether.userapi.logingift.service;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.domain.logingift.repository.LoginGiftParticipationRepository;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.userapi.logingift.dto.LoginGiftListResponse;
import com.triples.rougether.userapi.logingift.dto.LoginGiftListResponse.Gift;
import com.triples.rougether.userapi.logingift.dto.LoginGiftListResponse.Reward;
import com.triples.rougether.userapi.logingift.error.LoginGiftErrorCode;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoginGiftReceiptService {
    private final LoginGiftParticipationRepository participations;
    private final UserRepository users;
    private final Clock kstClock;

    public LoginGiftListResponse list(Long userId) {
        requireActiveUser(userId);
        return new LoginGiftListResponse(participations.findReceipts(userId).stream().map(p -> {
            var event = p.getEvent();
            var item = event.getRewardItem();
            return new Gift(p.getId(), event.getCode(), event.getTitle(), p.getEligibleAt(),
                    p.getGrantedAt(), p.getAcknowledgedAt(), event.getCoinAmount(),
                    new Reward(item.getId(), item.getName(), item.getAssetKey(),
                            p.getRewardUserItem() == null ? null : p.getRewardUserItem().getId()));
        }).toList());
    }

    @Transactional
    public void acknowledge(Long userId, Long participationId) {
        requireActiveUser(userId);
        var participation = participations.findById(participationId)
                .filter(p -> p.getUser().getId().equals(userId))
                .orElseThrow(() -> new BusinessException(LoginGiftErrorCode.LOGIN_GIFT_NOT_FOUND));
        participation.acknowledge(kstClock.instant());
    }

    private void requireActiveUser(Long userId) {
        users.findByIdAndDeletedAtIsNull(userId).filter(u -> u.isHuman())
                .orElseThrow(() -> new BusinessException(LoginGiftErrorCode.LOGIN_GIFT_NOT_FOUND));
    }
}
