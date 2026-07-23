package com.triples.rougether.adminapi.itemslot.dto;

import java.math.BigDecimal;

public record ItemRenderDefaultsUpdateRequest(
        BigDecimal defaultScale,
        BigDecimal defaultPositionX,
        BigDecimal defaultPositionY) {
}
