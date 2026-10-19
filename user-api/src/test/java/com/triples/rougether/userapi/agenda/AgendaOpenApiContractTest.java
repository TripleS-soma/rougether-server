package com.triples.rougether.userapi.agenda;

import static org.assertj.core.api.Assertions.assertThat;

import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.util.Json31;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springdoc.core.utils.SpringDocUtils;

class AgendaOpenApiContractTest {

    @ParameterizedTest
    @ValueSource(classes = {TodayResponse.class, CalendarDayResponse.class})
    void 공통_DTO의_스키마와_참조를_유지한다(Class<?> responseType) throws Exception {
        // 단독 실행도 서버 기동 후와 같은 LocalTime 등 기본 스키마 설정을 사용함
        SpringDocUtils.getConfig().initExtraSchemas();
        var resolved = new ModelConverters(true).resolveAsResolvedSchema(
                new AnnotatedType(responseType).resolveAsRef(true));
        var actual = Json31.mapper().valueToTree(Map.of(
                "response", resolved.schema,
                "schemas", resolved.referencedSchemas));

        assertThat(resolved.schema.get$ref()).isEqualTo("#/components/schemas/" + responseType.getSimpleName());
        assertThat(resolved.referencedSchemas).containsKeys(
                "TodayCategoryGroup", "TodayRoutineItem", "TodayTodoItem", "TodaySummary");
        // DTO 이동 전 코드에서 생성한 스키마와 전체 구조를 비교함
        try (var baseline = getClass().getResourceAsStream(
                "/contracts/agenda/" + responseType.getSimpleName() + ".json")) {
            assertThat(baseline).as("변경 전 OpenAPI 스키마").isNotNull();
            assertThat(actual).isEqualTo(Json31.mapper().readTree(baseline));
        }
    }
}
