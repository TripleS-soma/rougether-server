package com.triples.rougether.userapi.character.dto;

import com.triples.rougether.domain.character.entity.CharacterAccessoryRenderProfile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

public record AccessoryRenderProfileResponse(
        @Schema(description = "렌더 상태. default는 필수 fallback, 나머지는 프론트 포즈 상태와 일치할 때 사용",
                example = "default")
        String renderState,
        @Schema(description = "캐릭터 위에 합성할 단품 이미지 asset key")
        String assetKey,
        @Schema(description = "프로필 좌표 기준 캐릭터 원본 캔버스 너비", example = "180")
        int canvasWidth,
        @Schema(description = "프로필 좌표 기준 캐릭터 원본 캔버스 높이", example = "172")
        int canvasHeight,
        @Schema(description = "단품 이미지 원본 너비. 표시 높이 계산에 사용", example = "320")
        int assetWidth,
        @Schema(description = "단품 이미지 원본 높이. 표시 높이 계산에 사용", example = "160")
        int assetHeight,
        @Schema(description = "캐릭터 원본 캔버스 기준 악세사리 중심 X 좌표(0.0~1.0)", example = "0.50")
        BigDecimal positionX,
        @Schema(description = "캐릭터 원본 캔버스 기준 악세사리 중심 Y 좌표(0.0~1.0)", example = "0.31")
        BigDecimal positionY,
        @Schema(description = "악세사리 표시 너비 / 캐릭터 캔버스 너비 비율", example = "0.52")
        BigDecimal widthRatio,
        @Schema(description = "시계 방향 회전 각도(-360~360)", example = "0")
        int rotationDeg,
        @Schema(description = "캐릭터 합성 레이어 순서. 클수록 위", example = "20")
        int zIndex) {

    public static AccessoryRenderProfileResponse of(CharacterAccessoryRenderProfile profile) {
        return new AccessoryRenderProfileResponse(
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
