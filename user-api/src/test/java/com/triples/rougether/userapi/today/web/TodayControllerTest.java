package com.triples.rougether.userapi.today.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.routine.entity.AuthType;
import com.triples.rougether.domain.routine.entity.TodoStatus;
import com.triples.rougether.userapi.agenda.dto.AgendaCategoryGroup;
import com.triples.rougether.userapi.agenda.dto.AgendaRoutineItem;
import com.triples.rougether.userapi.agenda.dto.AgendaSummary;
import com.triples.rougether.userapi.agenda.dto.AgendaTodoItem;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import com.triples.rougether.userapi.today.dto.TodayResponse;
import com.triples.rougether.userapi.today.dto.TodayStreak;
import com.triples.rougether.userapi.today.service.TodayService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TodayController.class)
@AutoConfigureMockMvc(addFilters = false)
class TodayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TodayService todayService;
    @MockitoBean
    private CurrentUserArgumentResolver currentUserArgumentResolver;
    // JwtAuthenticationFilter가 슬라이스에 로드되며 요구함.
    @MockitoBean
    private TokenService tokenService;

    @BeforeEach
    void stubCurrentUser() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, MemberRole.NORMAL));
    }

    @Test
    void 응답은_categories_summary_streak_구조로_반환한다() throws Exception {
        TodayResponse response = new TodayResponse(LocalDate.of(2026, 6, 29),
                List.of(new AgendaCategoryGroup(3L,
                        List.of(new AgendaRoutineItem(10L, "아침 운동", LocalTime.of(7, 0),
                                AuthType.CHECK, true)),
                        List.of(new AgendaTodoItem(20L, "장보기", LocalDate.of(2026, 6, 29), null,
                                TodoStatus.PENDING, null))),
                        new AgendaCategoryGroup(null,
                                List.of(new AgendaRoutineItem(11L, "사진 인증", null,
                                        AuthType.PHOTO, false, 42L)),
                                List.of(new AgendaTodoItem(21L, "책 읽기", LocalDate.of(2026, 6, 29),
                                        LocalTime.of(20, 30), TodoStatus.COMPLETED,
                                        Instant.parse("2026-06-29T11:30:00Z"))))),
                new AgendaSummary(2, 2, 0.5),
                new TodayStreak(5, 9, LocalDate.of(2026, 6, 29)));
        when(todayService.today(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/today"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "date": "2026-06-29",
                          "categories": [
                            {
                              "categoryId": 3,
                              "routines": [
                                {
                                  "id": 10,
                                  "title": "아침 운동",
                                  "scheduledTime": "07:00:00",
                                  "authType": "CHECK",
                                  "completed": true,
                                  "houseMissionId": null
                                }
                              ],
                              "todos": [
                                {
                                  "id": 20,
                                  "title": "장보기",
                                  "dueDate": "2026-06-29",
                                  "dueTime": null,
                                  "status": "PENDING",
                                  "completedAt": null
                                }
                              ]
                            },
                            {
                              "categoryId": null,
                              "routines": [
                                {
                                  "id": 11,
                                  "title": "사진 인증",
                                  "scheduledTime": null,
                                  "authType": "PHOTO",
                                  "completed": false,
                                  "houseMissionId": 42
                                }
                              ],
                              "todos": [
                                {
                                  "id": 21,
                                  "title": "책 읽기",
                                  "dueDate": "2026-06-29",
                                  "dueTime": "20:30:00",
                                  "status": "COMPLETED",
                                  "completedAt": "2026-06-29T11:30:00Z"
                                }
                              ]
                            }
                          ],
                          "summary": {
                            "completedCount": 2,
                            "remainingCount": 2,
                            "progressRate": 0.5
                          },
                          "streak": {
                            "currentCount": 5,
                            "longestCount": 9,
                            "lastSuccessDate": "2026-06-29"
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.date").value("2026-06-29"))
                .andExpect(jsonPath("$.categories[0].categoryId").value(3))
                .andExpect(jsonPath("$.categories[0].routines[0].id").value(10))
                .andExpect(jsonPath("$.categories[0].routines[0].completed").value(true))
                .andExpect(jsonPath("$.categories[0].todos[0].status").value("PENDING"))
                .andExpect(jsonPath("$.summary.completedCount").value(2))
                .andExpect(jsonPath("$.summary.progressRate").value(0.5))
                .andExpect(jsonPath("$.streak.currentCount").value(5));
    }

    @Test
    void 빈_상태면_progressRate는_0이다() throws Exception {
        when(todayService.today(1L))
                .thenReturn(new TodayResponse(LocalDate.of(2026, 6, 29), List.of(),
                        new AgendaSummary(0, 0, 0.0), new TodayStreak(0, 0, null)));

        mockMvc.perform(get("/api/v1/today"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "date": "2026-06-29",
                          "categories": [],
                          "summary": {
                            "completedCount": 0,
                            "remainingCount": 0,
                            "progressRate": 0.0
                          },
                          "streak": {
                            "currentCount": 0,
                            "longestCount": 0,
                            "lastSuccessDate": null
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.summary.progressRate").value(0.0))
                .andExpect(jsonPath("$.categories").isEmpty());
    }
}
