package com.triples.rougether.userapi.furniture.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.infra.llm.LlmProperties;
import com.triples.rougether.userapi.furniture.FurnitureFixtures;
import com.triples.rougether.userapi.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.userapi.furniture.service.FurnitureAiFailure;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OpenAiFurnitureClientTest {
    private MockRestServiceServer server;
    private OpenAiFurnitureClient client;
    private FurnitureAiClient.Context context;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://openai.test/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        var llm = new LlmProperties("https://openai.test/v1", "other-model", "test-only-key", Duration.ofSeconds(30),
                null, 800, true, "low", 2, Duration.ofSeconds(1), "embedding", 1024);
        var config = new FurnitureGenerationProperties(true, "gpt-6-astra", "gpt-image-2", Duration.ofSeconds(180),
                3, 6, 2, Duration.ofHours(24), List.of("items/ref.png"));
        client = new OpenAiFurnitureClient(builder.build(), llm, config);
        byte[] sprite = FurnitureFixtures.png(true, false);
        context = new FurnitureAiClient.Context(sprite, List.of(sprite), sprite,
                "앞 의자", "다리가 이상해요", "다리만 수정");
    }

    @AfterEach void verifyCalls() { server.verify(); }

    @Test void Astra가_원본과_레퍼런스로_이미지_도구를_한번만_호출하도록_요청() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-only-key"))
                .andExpect(jsonPath("$.model").value("gpt-6-astra"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.max_tool_calls").value(1))
                .andExpect(jsonPath("$.tools[0].background").value("transparent"))
                .andExpect(jsonPath("$.tools[0].model").value("gpt-image-2"))
                .andExpect(jsonPath("$.tools[0].action").value("generate"))
                .andExpect(jsonPath("$.input[0].content.length()").value(5))
                .andRespond(withSuccess(generated(), MediaType.APPLICATION_JSON));
        assertThat(client.generate(context, Action.REGENERATE).image()).isNotEmpty();
    }

    @Test void 부분수정은_기존_후보를_이미지_입력으로_포함() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(jsonPath("$.tools[0].action").value("edit"))
                .andExpect(jsonPath("$.input[0].content.length()").value(7))
                .andRespond(withSuccess(generated(), MediaType.APPLICATION_JSON));
        client.generate(context, Action.EDIT);
    }

    @Test void 검수는_이미지_생성_도구_없이_엄격한_구조화_응답으로_분리() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.input[0].content.length()").value(9))
                .andRespond(withSuccess(review("EDIT", "다리를 고쳐 주세요"), MediaType.APPLICATION_JSON));
        var result = client.review(context, List.of("OBJECT_TOUCHES_CANVAS_BORDER"));
        assertThat(result.decision()).isEqualTo(FurnitureAiClient.Decision.EDIT);
        assertThat(result.correction()).isEqualTo("다리를 고쳐 주세요");
    }

    @Test void 알수없는_판정과_수정지시_없는_재시도를_거부() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andRespond(withSuccess(review("EXECUTE_SHELL", ""), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.review(context, List.of()))
                .isInstanceOf(FurnitureAiFailure.class).hasMessage("INVALID_REVIEW_OUTPUT");
    }

    @Test void 생성_거절과_불완전_응답을_성공으로_취급하지_않음() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andRespond(withSuccess("{\"status\":\"incomplete\",\"output\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.generate(context, Action.GENERATE)).hasMessage("PROVIDER_RESPONSE_INCOMPLETE");
    }

    @Test void 인증_오류_본문과_키를_노출하지_않고_자동_재시도하지_않음() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("secret response and user photograph"));
        assertThatThrownBy(() -> client.generate(context, Action.GENERATE))
                .hasMessage("PROVIDER_AUTH_FAILED").hasNoCause();
    }

    @Test void 공급자_429도_숨은_재호출_없이_작업자에게_전달() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.generate(context, Action.GENERATE)).hasMessage("PROVIDER_RATE_LIMITED");
    }

    private String generated() {
        return json.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of(
                "type", "image_generation_call", "status", "completed", "result",
                Base64.getEncoder().encodeToString(FurnitureFixtures.png(true, false)))),
                "usage", Map.of("input_tokens", 100, "output_tokens", 50)));
    }
    private String review(String decision, String correction) {
        String text = json.writeValueAsString(Map.of("decision", decision, "name", "의자", "reason", "검수 결과", "correction", correction));
        return json.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message",
                "content", List.of(Map.of("type", "output_text", "text", text))))));
    }
}
