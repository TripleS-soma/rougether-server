package com.triples.rougether.batch.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JevClientTest {

    private static final String BASE_URL = "https://api.typesafe.ai";
    private static final String URL = BASE_URL + "/v1/systemone";

    private MockRestServiceServer server;
    private List<Duration> sleeps;
    private JevClient client;

    static String response(double spam, double abuse, double sexual, double violence, double personal, double none) {
        return """
                {"model":"jev-1.14.0","answers":{"harm":{"choice":"NONE","confidence":0.9,
                 "probabilities":{"SPAM":%s,"ABUSE":%s,"SEXUAL":%s,"VIOLENCE":%s,"PERSONAL_INFO":%s,"NONE":%s}}},
                 "usage":{"input_tokens":123,"output_tokens":0}}
                """.formatted(spam, abuse, sexual, violence, personal, none);
    }

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sleeps = new ArrayList<>();
        ContentScreeningProperties properties = new ContentScreeningProperties("ts-test", BASE_URL, "jev-1.14.0",
                0.6, 50, 7, Duration.ofSeconds(5), 2, Duration.ofSeconds(1));
        client = new JevClient(builder.baseUrl(BASE_URL).build(), properties, sleeps::add);
    }

    @Test
    void 본문과_문맥만_choice_질문으로_보내고_확률과_토큰을_돌려준다() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer ts-test"))
                .andExpect(jsonPath("$.model").value("jev-1.14.0"))
                .andExpect(jsonPath("$.state.text").value("광고 링크 클릭"))
                .andExpect(jsonPath("$.state.context").value("원 게시글"))
                .andExpect(jsonPath("$.state.author").doesNotExist())
                .andExpect(jsonPath("$.questions.harm.type").value("choice"))
                .andExpect(jsonPath("$.questions.harm.criteria.SPAM").exists())
                .andExpect(jsonPath("$.questions.harm.criteria.NONE").exists())
                .andRespond(withSuccess(response(0.7, 0.05, 0.05, 0.05, 0.05, 0.1), MediaType.APPLICATION_JSON));

        JevVerdict verdict = client.classify("광고 링크 클릭", "원 게시글");

        assertThat(verdict.probabilities()).containsEntry("SPAM", 0.7).containsEntry("NONE", 0.1).hasSize(6);
        assertThat(verdict.inputTokens()).isEqualTo(123);
        assertThat(verdict.model()).isEqualTo("jev-1.14.0");
        server.verify();
    }

    @Test
    void 글은_문맥_없이_보낸다() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.state.context").doesNotExist())
                .andRespond(withSuccess(response(0, 0, 0, 0, 0, 1), MediaType.APPLICATION_JSON));
        client.classify("오늘 점심 뭐 먹지", null);
        server.verify();
    }

    @Test
    void 요청과다_429와_과부하_529는_백오프로_재시도한_뒤_성공한다() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatusCode.valueOf(529)));
        server.expect(requestTo(URL)).andRespond(withSuccess(response(0, 0, 0, 0, 0, 1), MediaType.APPLICATION_JSON));

        client.classify("안녕", null);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
        server.verify();
    }

    @Test
    void 재시도를_다_쓰면_retryable_예외를_던진다() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.classify("안녕", null))
                .isInstanceOf(JevException.class)
                .matches(e -> ((JevException) e).isRetryable());
        server.verify();
    }

    @Test
    void 인증_실패는_재시도하지_않고_JevAuthException() {
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.classify("안녕", null)).isInstanceOf(JevAuthException.class);
        assertThat(sleeps).isEmpty();
        server.verify();
    }

    @Test
    void 그_외_4xx는_재시도하지_않는다() {
        server.expect(times(1), requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> client.classify("안녕", null))
                .isInstanceOf(JevException.class)
                .matches(e -> !((JevException) e).isRetryable());
        server.verify();
    }

    @Test
    void 네트워크_오류도_재시도한다() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new java.io.IOException("connection reset");
        });
        server.expect(requestTo(URL)).andRespond(withSuccess(response(0, 0, 0, 0, 0, 1), MediaType.APPLICATION_JSON));
        client.classify("안녕", null);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        server.verify();
    }

    @Test
    void 응답_모델명이_비었거나_저장_길이를_넘으면_요청한_고정_모델명을_쓴다() {
        String longModel = response(0, 0, 0, 0, 0, 1).replace("\"jev-1.14.0\"", "\"" + "m".repeat(41) + "\"");
        assertThat(client.parse(longModel).model()).isEqualTo("jev-1.14.0");
        String noModel = response(0, 0, 0, 0, 0, 1).replace("\"model\":\"jev-1.14.0\",", "");
        assertThat(client.parse(noModel).model()).isEqualTo("jev-1.14.0");
    }

    @Test
    void 확률_합이_1이_아니거나_항목이_다르거나_범위를_벗어나면_거부한다() {
        assertThatThrownBy(() -> client.parse(response(0.5, 0.5, 0.5, 0, 0, 0)))
                .isInstanceOf(JevException.class).hasMessageContaining("합");
        assertThatThrownBy(() -> client.parse(response(-0.1, 0.1, 0, 0, 0, 1)))
                .isInstanceOf(JevException.class).hasMessageContaining("범위");
        assertThatThrownBy(() -> client.parse("""
                {"answers":{"harm":{"probabilities":{"SPAM":0.5,"NONE":0.5}}}}"""))
                .isInstanceOf(JevException.class).hasMessageContaining("항목");
        assertThatThrownBy(() -> client.parse("""
                {"answers":{"harm":{"probabilities":{"SPAM":0.1,"ABUSE":0.1,"SEXUAL":0.1,"VIOLENCE":0.1,"OTHER":0.1,"NONE":0.5}}}}"""))
                .isInstanceOf(JevException.class).hasMessageContaining("PERSONAL_INFO");
        assertThatThrownBy(() -> client.parse("not json")).isInstanceOf(JevException.class);
    }
}
