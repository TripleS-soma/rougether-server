package com.triples.rougether.adminapi.accessoryrender.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

final class StrictIntegerDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(
            JsonParser parser,
            DeserializationContext context) throws JacksonException {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return context.reportInputMismatch(
                    Integer.class,
                    "정수 JSON 값이 필요합니다.");
        }
        return parser.getIntValue();
    }
}
