package com.triples.rougether.adminapi.accessoryrender.dto;

import java.math.BigDecimal;

public record AccessoryRenderProfileTransformUpdateRequest(
        BigDecimal positionX,
        BigDecimal positionY,
        BigDecimal widthRatio,
        Integer rotationDeg,
        Integer zIndex) {
}
