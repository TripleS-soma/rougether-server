package com.triples.rougether.userapi.minigame;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.common.error.BusinessException;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.minigame.dto.MinigameAction;
import com.triples.rougether.userapi.minigame.dto.MinigameDirection;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishRequest;
import com.triples.rougether.userapi.minigame.dto.MinigameFinishResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameLeaderboardResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameListResponse;
import com.triples.rougether.userapi.minigame.dto.MinigameRunStartResponse;
import com.triples.rougether.userapi.minigame.error.MinigameErrorCode;
import com.triples.rougether.userapi.minigame.service.MinigameCatalog;
import com.triples.rougether.userapi.minigame.service.MinigameCommandService;
import com.triples.rougether.userapi.minigame.service.MinigameQueryService;
import com.triples.rougether.userapi.minigame.web.MinigameController;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MinigameController.class)
@AutoConfigureMockMvc(addFilters = false)
class MinigameControllerTest {

    private static final String GAME_CODE = "room-runner";
    private static final String RUN_ID = "4a86260f-46d5-4fd3-870a-8f15f1ca17f3";
    private static final String FINISH_PATH = "/api/v1/minigames/" + GAME_CODE + "/runs/" + RUN_ID + "/finish";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MinigameCatalog minigameCatalog;
    @MockitoBean
    private MinigameCommandService minigameCommandService;
    @MockitoBean
    private MinigameQueryService minigameQueryService;
    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean
    private TokenService tokenService;

    @BeforeEach
    void stubAuth() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(7L, null));
    }

    @Test
    void 게임_목록은_게임코드와_규칙버전을_포함한다() throws Exception {
        when(minigameCatalog.list()).thenReturn(new MinigameListResponse(List.of(
                new MinigameListResponse.Item(GAME_CODE, "루틴 러너", "탭해서 장애물을 넘어요.", 1))));

        mockMvc.perform(get("/api/v1/minigames"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].gameCode").value(GAME_CODE))
                .andExpect(jsonPath("$.items[0].name").value("루틴 러너"))
                .andExpect(jsonPath("$.items[0].rulesVersion").value(1));
    }

    @Test
    void 게임_시작은_인증_사용자로_세션을_만들고_201로_응답한다() throws Exception {
        when(minigameCommandService.start(7L, GAME_CODE)).thenReturn(new MinigameRunStartResponse(
                RUN_ID, GAME_CODE, 1, 42, 18000, Instant.parse("2026-09-12T06:10:00Z")));

        mockMvc.perform(post("/api/v1/minigames/{gameCode}/runs", GAME_CODE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(RUN_ID))
                .andExpect(jsonPath("$.gameCode").value(GAME_CODE))
                .andExpect(jsonPath("$.rulesVersion").value(1))
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.maxTicks").value(18000))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-12T06:10:00Z"));

        verify(minigameCommandService).start(7L, GAME_CODE);
    }

    @Test
    void 종료_요청은_인증_사용자와_입력을_전달하고_검증된_점수와_순위를_반환한다() throws Exception {
        MinigameFinishRequest request = new MinigameFinishRequest(800, List.of(50, 130));
        when(minigameCommandService.finish(7L, GAME_CODE, RUN_ID, request))
                .thenReturn(new MinigameFinishResponse(RUN_ID, 133, 133, true, 2));

        mockMvc.perform(post(FINISH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticks":800,"jumpTicks":[50,130]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(RUN_ID))
                .andExpect(jsonPath("$.score").value(133))
                .andExpect(jsonPath("$.bestScore").value(133))
                .andExpect(jsonPath("$.personalBest").value(true))
                .andExpect(jsonPath("$.rank").value(2));

        verify(minigameCommandService).finish(7L, GAME_CODE, RUN_ID, request);
    }

    @Test
    void 점프하지_않은_종료_기록도_검증_서비스로_전달한다() throws Exception {
        MinigameFinishRequest request = new MinigameFinishRequest(400, List.of());
        when(minigameCommandService.finish(7L, GAME_CODE, RUN_ID, request))
                .thenReturn(new MinigameFinishResponse(RUN_ID, 66, 133, false, 2));

        mockMvc.perform(post(FINISH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticks":400,"jumpTicks":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalBest").value(false));

        verify(minigameCommandService).finish(7L, GAME_CODE, RUN_ID, request);
    }

    @Test
    void 전체_랭킹과_인증_사용자의_순위를_함께_반환한다() throws Exception {
        var first = new MinigameLeaderboardResponse.Entry(1, 9, "달리미", 500);
        var mine = new MinigameLeaderboardResponse.Entry(2, 7, "루티니", 133);
        when(minigameQueryService.leaderboard(7L, GAME_CODE))
                .thenReturn(new MinigameLeaderboardResponse(List.of(first, mine), mine, 2));

        mockMvc.perform(get("/api/v1/minigames/{gameCode}/leaderboard", GAME_CODE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].userId").value(9))
                .andExpect(jsonPath("$.items[0].nickname").value("달리미"))
                .andExpect(jsonPath("$.items[0].score").value(500))
                .andExpect(jsonPath("$.myEntry.userId").value(7))
                .andExpect(jsonPath("$.myEntry.rank").value(2))
                .andExpect(jsonPath("$.totalPlayers").value(2));

        verify(minigameQueryService).leaderboard(7L, GAME_CODE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"jumpTicks\":[]}",
            "{\"ticks\":null,\"jumpTicks\":[]}",
            "{\"ticks\":0,\"jumpTicks\":[]}",
            "{\"ticks\":18001,\"jumpTicks\":[]}",
            "{\"ticks\":400}",
            "{\"ticks\":400,\"jumpTicks\":null}",
            "{\"ticks\":400,\"jumpTicks\":[null]}",
            "{\"ticks\":400,\"jumpTicks\":[0]}",
            "{\"ticks\":400,\"jumpTicks\":[18001]}"
    })
    void 누락되거나_범위를_벗어난_종료_입력은_검증_전에_400으로_거절한다(String body) throws Exception {
        mockMvc.perform(post(FINISH_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(minigameCommandService);
    }

    @Test
    void 점프_입력이_600개를_초과하면_400으로_거절한다() throws Exception {
        String body = "{\"ticks\":18000,\"jumpTicks\":[" + "1,".repeat(600) + "1]}";

        mockMvc.perform(post(FINISH_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(minigameCommandService);
    }

    @Test
    void 지원하지_않는_게임은_404와_게임_에러코드를_반환한다() throws Exception {
        when(minigameCommandService.start(7L, "unknown"))
                .thenThrow(new BusinessException(MinigameErrorCode.GAME_NOT_FOUND));

        mockMvc.perform(post("/api/v1/minigames/unknown/runs"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MINIGAME_NOT_FOUND"));
    }

    @Test
    void 소유하지_않은_게임_기록은_404로_응답한다() throws Exception {
        when(minigameCommandService.finish(eq(7L), eq(GAME_CODE), eq(RUN_ID), any()))
                .thenThrow(new BusinessException(MinigameErrorCode.RUN_NOT_FOUND));

        mockMvc.perform(post(FINISH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticks\":400,\"jumpTicks\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MINIGAME_RUN_NOT_FOUND"));
    }

    @Test
    void 만료된_게임_기록은_410으로_응답한다() throws Exception {
        when(minigameCommandService.finish(eq(7L), eq(GAME_CODE), eq(RUN_ID), any()))
                .thenThrow(new BusinessException(MinigameErrorCode.RUN_EXPIRED));

        mockMvc.perform(post(FINISH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticks\":400,\"jumpTicks\":[]}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("MINIGAME_RUN_EXPIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cat-stairs", "cat-merge"})
    void 방향_입력_게임도_인증_사용자와_게임코드로_시작한다(String gameCode) throws Exception {
        when(minigameCommandService.start(7L, gameCode)).thenReturn(new MinigameRunStartResponse(
                RUN_ID, gameCode, 1, 42, 18000, Instant.parse("2026-09-12T06:10:00Z")));

        mockMvc.perform(post("/api/v1/minigames/{gameCode}/runs", gameCode))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runId").value(RUN_ID))
                .andExpect(jsonPath("$.gameCode").value(gameCode))
                .andExpect(jsonPath("$.seed").value(42));

        verify(minigameCommandService).start(7L, gameCode);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cat-stairs", "cat-merge"})
    void 방향_입력_종료_요청은_점프_필드_없이_게임코드와_actions를_전달한다(String gameCode) throws Exception {
        MinigameFinishRequest request = new MinigameFinishRequest(180, null,
                List.of(new MinigameAction(1, MinigameDirection.LEFT)));
        when(minigameCommandService.finish(7L, gameCode, RUN_ID, request))
                .thenReturn(new MinigameFinishResponse(RUN_ID, 10, 20, false, 3));

        mockMvc.perform(post("/api/v1/minigames/{gameCode}/runs/{runId}/finish", gameCode, RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticks":180,"actions":[{"tick":1,"direction":"LEFT"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(RUN_ID))
                .andExpect(jsonPath("$.score").value(10))
                .andExpect(jsonPath("$.bestScore").value(20))
                .andExpect(jsonPath("$.personalBest").value(false))
                .andExpect(jsonPath("$.rank").value(3));

        verify(minigameCommandService).finish(7L, gameCode, RUN_ID, request);
    }

    @Test
    void 합치기_게임은_네_방향과_입력_순서를_유지해_검증_서비스로_전달한다() throws Exception {
        MinigameFinishRequest request = new MinigameFinishRequest(180, null, List.of(
                new MinigameAction(1, MinigameDirection.LEFT),
                new MinigameAction(20, MinigameDirection.RIGHT),
                new MinigameAction(40, MinigameDirection.UP),
                new MinigameAction(60, MinigameDirection.DOWN)));
        when(minigameCommandService.finish(7L, "cat-merge", RUN_ID, request))
                .thenReturn(new MinigameFinishResponse(RUN_ID, 40, 40, true, 1));

        mockMvc.perform(post("/api/v1/minigames/cat-merge/runs/{runId}/finish", RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticks":180,"actions":[
                                  {"tick":1,"direction":"LEFT"},
                                  {"tick":20,"direction":"RIGHT"},
                                  {"tick":40,"direction":"UP"},
                                  {"tick":60,"direction":"DOWN"}
                                ]}
                                """))
                .andExpect(status().isOk());

        verify(minigameCommandService).finish(7L, "cat-merge", RUN_ID, request);
    }

    @ParameterizedTest
    @MethodSource("invalidDirectionReplayRequests")
    void 입력_종류가_중복되거나_방향_입력이_잘못되면_400으로_거절한다(String gameCode, String body)
            throws Exception {
        mockMvc.perform(post("/api/v1/minigames/{gameCode}/runs/{runId}/finish", gameCode, RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(minigameCommandService);
    }

    private static Stream<Arguments> invalidDirectionReplayRequests() {
        return Stream.of("cat-stairs", "cat-merge").flatMap(gameCode -> Stream.of(
                "{\"ticks\":180}",
                "{\"ticks\":180,\"jumpTicks\":null,\"actions\":null}",
                "{\"ticks\":180,\"jumpTicks\":[],\"actions\":[]}",
                "{\"ticks\":180,\"jumpTicks\":[1],\"actions\":[{\"tick\":1,\"direction\":\"LEFT\"}]}",
                "{\"ticks\":180,\"actions\":[null]}",
                "{\"ticks\":180,\"actions\":[{}]}",
                "{\"ticks\":180,\"actions\":[{\"direction\":\"LEFT\"}]}",
                "{\"ticks\":180,\"actions\":[{\"tick\":null,\"direction\":\"LEFT\"}]}",
                "{\"ticks\":180,\"actions\":[{\"tick\":0,\"direction\":\"LEFT\"}]}",
                "{\"ticks\":180,\"actions\":[{\"tick\":18001,\"direction\":\"LEFT\"}]}",
                "{\"ticks\":180,\"actions\":[{\"tick\":1}]}",
                "{\"ticks\":180,\"actions\":[{\"tick\":1,\"direction\":null}]}"
        ).map(body -> Arguments.of(gameCode, body)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"cat-stairs", "cat-merge"})
    void 지원하지_않는_방향_문자열은_본문_해석_오류로_400을_반환한다(String gameCode) throws Exception {
        mockMvc.perform(post("/api/v1/minigames/{gameCode}/runs/{runId}/finish", gameCode, RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticks":180,"actions":[{"tick":1,"direction":"SIDEWAYS"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verifyNoInteractions(minigameCommandService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cat-stairs", "cat-merge"})
    void 방향_입력이_2000개를_초과하면_400으로_거절한다(String gameCode) throws Exception {
        String action = "{\"tick\":1,\"direction\":\"LEFT\"}";
        String body = "{\"ticks\":18000,\"actions\":[" + (action + ",").repeat(2000) + action + "]}";

        mockMvc.perform(post("/api/v1/minigames/{gameCode}/runs/{runId}/finish", gameCode, RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(minigameCommandService);
    }

    @ParameterizedTest
    @MethodSource("directionReplayErrors")
    void 방향_입력_게임의_검증_오류는_서비스_에러코드와_HTTP상태로_응답한다(
            String gameCode, MinigameErrorCode errorCode) throws Exception {
        MinigameFinishRequest request = new MinigameFinishRequest(180, null,
                List.of(new MinigameAction(1, MinigameDirection.LEFT)));
        when(minigameCommandService.finish(7L, gameCode, RUN_ID, request))
                .thenThrow(new BusinessException(errorCode));

        mockMvc.perform(post("/api/v1/minigames/{gameCode}/runs/{runId}/finish", gameCode, RUN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticks":180,"actions":[{"tick":1,"direction":"LEFT"}]}
                                """))
                .andExpect(status().is(errorCode.status()))
                .andExpect(jsonPath("$.code").value(errorCode.code()));

        verify(minigameCommandService).finish(7L, gameCode, RUN_ID, request);
    }

    private static Stream<Arguments> directionReplayErrors() {
        return Stream.of("cat-stairs", "cat-merge").flatMap(gameCode -> Stream.of(
                MinigameErrorCode.RUN_NOT_FOUND,
                MinigameErrorCode.INVALID_REPLAY,
                MinigameErrorCode.RUN_EXPIRED,
                MinigameErrorCode.RUN_ALREADY_FINISHED
        ).map(errorCode -> Arguments.of(gameCode, errorCode)));
    }
}
