package com.triples.rougether.adminapi.accessoryrender.dto;

import java.math.BigDecimal;

public record AccessoryRenderProfileImportRequest(
        String itemAssetKey,
        String characterCode,
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
}
