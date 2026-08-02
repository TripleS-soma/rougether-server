package com.triples.rougether.userapi.character.dto;

import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import com.triples.rougether.domain.character.entity.UserCharacterAccessory;
import com.triples.rougether.domain.shop.entity.Item;
import com.triples.rougether.domain.shop.entity.UserItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public record EquippedAccessoryResponse(
        @Schema(description = "보유 아이템 ID", example = "77")
        Long userItemId,
        @Schema(description = "아이템 마스터 ID", example = "15")
        Long itemId,
        @Schema(description = "악세사리 이름", example = "검은 뿔테안경")
        String name,
        @Schema(description = "캐릭터 위에 합성할 이미지 asset key")
        String assetKey,
        @Schema(description = "캐릭터 착용 슬롯", example = "eyewear")
        String characterSlotType,
        @Schema(description = "현재 캐릭터에서 사용할 상태별 합성 위치. 일치 상태가 없으면 default를 쓰며, "
                + "default만 있을 때는 idle 렌더를 권장")
        List<AccessoryRenderProfileResponse> renderProfiles,
        @Schema(description = "현재 슬롯에 적용한 시각")
        Instant equippedAt) {

    public static EquippedAccessoryResponse of(
            UserCharacterAccessory accessory,
            List<CharacterAccessoryRenderProfile> renderProfiles) {
        UserItem userItem = accessory.getUserItem();
        Item item = userItem.getItem();
        return new EquippedAccessoryResponse(
                userItem.getId(),
                item.getId(),
                item.getName(),
                item.getAssetKey(),
                accessory.getCharacterSlotType(),
                renderProfiles.stream().map(AccessoryRenderProfileResponse::of).toList(),
                accessory.getEquippedAt());
    }
}
