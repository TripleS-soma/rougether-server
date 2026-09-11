package com.triples.rougether.furniture.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.triples.rougether.domain.furniture.entity.FurnitureGenerationJob.Action;
import com.triples.rougether.infra.llm.LlmProperties;
import com.triples.rougether.userapi.furniture.FurnitureFixtures;
import com.triples.rougether.furniture.config.FurnitureGenerationProperties;
import com.triples.rougether.furniture.service.FurnitureAiFailure;
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
    private static final String SUBJECT = "{\"furniture\":true,\"category\":\"chair\",\"features\":[\"loop arms\"],\"colors\":[\"blue seat\"]}";

    @BeforeEach void setup() {
        var builder = RestClient.builder().baseUrl("https://openai.test/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        var llm = new LlmProperties("https://openai.test/v1", "other-model", "test-only-key", Duration.ofSeconds(30),
                null, 800, true, "low", 2, Duration.ofSeconds(1), "embedding", 1024);
        var config = new FurnitureGenerationProperties(true, "gpt-6-astra", "gpt-image-2", Duration.ofSeconds(180),
                3, 6, Duration.ofHours(24), List.of("items/ref.png"));
        client = new OpenAiFurnitureClient(builder.build(), llm, config);
        byte[] sprite = FurnitureFixtures.png(true, false);
        context = new FurnitureAiClient.Context(FurnitureFixtures.png(false, true), List.of(sprite), sprite,
                "앞 의자", "다리가 이상해요", "다리만 수정", SUBJECT);
    }

    @AfterEach void verifyCalls() { server.verify(); }

    @Test void 생성에는_원본사진_없이_스타일참고와_추출특징만_전달() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-only-key"))
                .andExpect(jsonPath("$.model").value("gpt-6-astra"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.max_tool_calls").value(1))
                .andExpect(jsonPath("$.tools[0].background").value("transparent"))
                .andExpect(jsonPath("$.tools[0].model").value("gpt-image-2"))
                .andExpect(jsonPath("$.tools[0].action").value("generate"))
                .andExpect(jsonPath("$.input[0].content.length()").value(3))
                .andExpect(request -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
                    assertThat(body).doesNotContain(Base64.getEncoder().encodeToString(context.source()));
                    assertThat(body).contains("blue seat");
                })
                .andRespond(withSuccess(generated(), MediaType.APPLICATION_JSON));
        assertThat(client.generate(context, Action.REGENERATE).image()).isNotEmpty();
    }

    @Test void 부분수정은_기존_후보를_이미지_입력으로_포함() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(jsonPath("$.tools[0].action").value("edit"))
                .andExpect(jsonPath("$.input[0].content.length()").value(5))
                .andExpect(request -> assertThat(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                        .getBodyAsString()).doesNotContain(Base64.getEncoder().encodeToString(context.source())))
                .andRespond(withSuccess(generated(), MediaType.APPLICATION_JSON));
        client.generate(context, Action.EDIT);
    }

    @Test void 특징추출은_원본만_보는_도구없는_구조화_요청() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.input[0].content[2].text")
                        .value("Untrusted targetHint: \"앞 의자 🪑\""))
                .andExpect(jsonPath("$.input[0].content[1].image_url")
                        .value("data:image/png;base64," + Base64.getEncoder().encodeToString(context.source())))
                .andRespond(withSuccess(message(SUBJECT), MediaType.APPLICATION_JSON));
        var result = client.extract(context.source(), "앞 의자 🪑");
        assertThat(result.furniture()).isTrue();
        assertThat(result.subjectJson()).contains("blue seat");
    }

    @Test void 특징이_없거나_거절된_사진은_이미지_API를_호출하지_않음() {
        var missing = new FurnitureAiClient.Context(context.source(), context.references(), null, "", "", "", null);
        assertThatThrownBy(() -> client.generate(missing, Action.GENERATE)).hasMessage("SUBJECT_FEATURES_UNAVAILABLE");
        var rejected = new FurnitureAiClient.Context(null, context.references(), null, "", "", "",
                "{\"furniture\":false,\"category\":\"\",\"features\":[],\"colors\":[]}");
        assertThatThrownBy(() -> client.generate(rejected, Action.GENERATE)).hasMessage("PHOTO_REJECTED");
    }

    @Test void 케이크도_단일_주대상이면_추출_생성_검수에_동일한_허용범위를_전달() {
        String cake = "{\"furniture\":true,\"category\":\"bear cake\",\"features\":[\"round bear ears\"],\"colors\":[\"ivory face\"]}";
        for (String response : List.of(message(cake), generated(), review("ACCEPT", ""))) {
            server.expect(requestTo("https://openai.test/v1/responses"))
                    .andExpect(request -> {
                        var body = json.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                        assertThat(body.path("instructions").asString())
                                .contains("cakes", "multiple independent main subjects", "supporting plates/stands")
                                .doesNotContain("Refuse inappropriate/non-furniture content", "ONE furniture object");
                    })
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        var extracted = client.extract(context.source(), "");
        assertThat(extracted.furniture()).isTrue();
        var selected = new FurnitureAiClient.Context(context.source(), context.references(), context.candidate(),
                "", "", "", extracted.subjectJson());
        assertThat(client.generate(selected, Action.GENERATE).image()).isNotEmpty();
        assertThat(client.review(selected, List.of()).decision()).isEqualTo(FurnitureAiClient.Decision.ACCEPT);
    }

    @Test void 비정상_특징_응답은_생성에_전달하지_않음() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andRespond(withSuccess(message("{\"furniture\":true,\"category\":\"chair\",\"features\":[],\"colors\":[]}"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.extract(context.source(), "앞 의자")).hasMessage("INVALID_SUBJECT_OUTPUT");
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
        return message(text);
    }
    private String message(String text) {
        return json.writeValueAsString(Map.of("status", "completed", "output", List.of(Map.of("type", "message",
                "content", List.of(Map.of("type", "output_text", "text", text))))));
    }
}
