package com.triples.rougether.userapi.logingift.dto;

import java.time.Instant;
import java.util.List;

public record LoginGiftListResponse(List<Gift> gifts) {
    public record Gift(Long participationId, String code, String title, Instant eligibleAt,
                       Instant grantedAt, Instant acknowledgedAt, int coinAmount, Reward reward) {}
    public record Reward(Long itemId, String name, String assetKey, Long userItemId) {}
}
