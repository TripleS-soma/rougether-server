package com.triples.rougether.adminapi.accessoryrender.dto;

import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import java.math.BigDecimal;

public record AccessoryRenderProfileRow(
        Long id,
        Long itemId,
        String itemName,
        String itemAssetKey,
        String characterSlotType,
        Long characterId,
        String characterCode,
        String characterName,
        String characterAssetKey,
        String renderState,
        String assetKey,
        int canvasWidth,
        int canvasHeight,
        int assetWidth,
        int assetHeight,
        BigDecimal positionX,
        BigDecimal positionY,
        BigDecimal widthRatio,
        int rotationDeg,
        int zIndex) {

    public static AccessoryRenderProfileRow of(CharacterAccessoryRenderProfile profile) {
        return new AccessoryRenderProfileRow(
                profile.getId(),
                profile.getItem().getId(),
                profile.getItem().getName(),
                profile.getItem().getAssetKey(),
                profile.getItem().getCharacterSlotType(),
                profile.getCharacter().getId(),
                profile.getCharacter().getCode(),
                profile.getCharacter().getName(),
                profile.getCharacter().getBaseAssetKey(),
                profile.getRenderState(),
                profile.getAssetKey(),
                profile.getCanvasWidth(),
                profile.getCanvasHeight(),
                profile.getAssetWidth(),
                profile.getAssetHeight(),
                profile.getPositionX(),
                profile.getPositionY(),
                profile.getWidthRatio(),
                profile.getRotationDeg(),
                profile.getZIndex());
    }
}
