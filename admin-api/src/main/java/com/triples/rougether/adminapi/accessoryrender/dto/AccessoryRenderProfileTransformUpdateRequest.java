package com.triples.rougether.adminapi.accessoryrender.dto;

import java.math.BigDecimal;
import tools.jackson.databind.annotation.JsonDeserialize;

public record AccessoryRenderProfileTransformUpdateRequest(
        BigDecimal positionX,
        BigDecimal positionY,
        BigDecimal widthRatio,
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        Integer rotationDeg,
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        Integer zIndex) {
}
