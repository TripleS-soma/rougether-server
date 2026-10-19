package com.triples.rougether.userapi.calendar.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.triples.rougether.userapi.calendar.dto.CalendarDayCount;
import com.triples.rougether.userapi.calendar.dto.CalendarDayResponse;
import com.triples.rougether.userapi.calendar.dto.CalendarMonthResponse;
import com.triples.rougether.userapi.calendar.service.CalendarService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import com.triples.rougether.userapi.global.security.MemberRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CalendarController.class)
@AutoConfigureMockMvc(addFilters = false)
class CalendarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CalendarService calendarService;
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
    void 응답은_date_categories_summary_구조로_반환한다() throws Exception {
        CalendarDayResponse response = new CalendarDayResponse(LocalDate.of(2026, 6, 29),
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
                new AgendaSummary(2, 2, 0.5));
        when(calendarService.day(eq(1L), eq(LocalDate.of(2026, 6, 29)))).thenReturn(response);

        mockMvc.perform(get("/api/v1/calendar").param("date", "2026-06-29"))
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
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.date").value("2026-06-29"))
                .andExpect(jsonPath("$.categories[0].categoryId").value(3))
                .andExpect(jsonPath("$.categories[0].routines[0].id").value(10))
                .andExpect(jsonPath("$.categories[0].routines[0].completed").value(true))
                .andExpect(jsonPath("$.categories[0].todos[0].status").value("PENDING"))
                .andExpect(jsonPath("$.summary.completedCount").value(2))
                .andExpect(jsonPath("$.summary.progressRate").value(0.5));
    }

    @Test
    void 과거_날짜도_동일한_계약으로_반환한다() throws Exception {
        // 과거는 완료 log 기반이라 노출 루틴은 모두 completed=true, categoryId는 삭제된 카테고리를 가리킬 수 있음
        CalendarDayResponse response = new CalendarDayResponse(LocalDate.of(2026, 6, 29),
                List.of(new AgendaCategoryGroup(99L,
                        List.of(new AgendaRoutineItem(10L, "삭제된 루틴", LocalTime.of(7, 0),
                                AuthType.CHECK, true)),
                        List.of())),
                new AgendaSummary(1, 0, 1.0));
        when(calendarService.day(eq(1L), eq(LocalDate.of(2026, 6, 29)))).thenReturn(response);

        mockMvc.perform(get("/api/v1/calendar").param("date", "2026-06-29"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "date": "2026-06-29",
                          "categories": [
                            {
                              "categoryId": 99,
                              "routines": [
                                {
                                  "id": 10,
                                  "title": "삭제된 루틴",
                                  "scheduledTime": "07:00:00",
                                  "authType": "CHECK",
                                  "completed": true,
                                  "houseMissionId": null
                                }
                              ],
                              "todos": []
                            }
                          ],
                          "summary": {
                            "completedCount": 1,
                            "remainingCount": 0,
                            "progressRate": 1.0
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.categories[0].categoryId").value(99))
                .andExpect(jsonPath("$.categories[0].routines[0].completed").value(true))
                .andExpect(jsonPath("$.summary.progressRate").value(1.0));
    }

    @Test
    void date_파라미터가_없으면_400() throws Exception {
        mockMvc.perform(get("/api/v1/calendar"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void date_형식이_잘못되면_400() throws Exception {
        mockMvc.perform(get("/api/v1/calendar").param("date", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    // --- 월별 개수 ---

    @Test
    void 월별_응답은_기존_전체수와_추가된_완료수를_함께_반환한다() throws Exception {
        YearMonth yearMonth = YearMonth.of(2026, 8);
        CalendarMonthResponse response = new CalendarMonthResponse(yearMonth, List.of(
                new CalendarDayCount(LocalDate.of(2026, 8, 1), 3, 1, 2, 1),
                new CalendarDayCount(LocalDate.of(2026, 8, 2), 0, 0, 0, 0)));
        when(calendarService.month(eq(1L), eq(yearMonth))).thenReturn(response);

        mockMvc.perform(get("/api/v1/calendar/month").param("yearMonth", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "yearMonth": "2026-08",
                          "days": [
                            {
                              "date": "2026-08-01",
                              "routineCount": 3,
                              "todoCount": 1,
                              "routineCompletedCount": 2,
                              "todoCompletedCount": 1
                            },
                            {
                              "date": "2026-08-02",
                              "routineCount": 0,
                              "todoCount": 0,
                              "routineCompletedCount": 0,
                              "todoCompletedCount": 0
                            }
                          ]
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.yearMonth").value("2026-08"))
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].date").value("2026-08-01"))
                .andExpect(jsonPath("$.days[0].routineCount").value(3))
                .andExpect(jsonPath("$.days[0].todoCount").value(1))
                .andExpect(jsonPath("$.days[0].routineCompletedCount").value(2))
                .andExpect(jsonPath("$.days[0].todoCompletedCount").value(1))
                .andExpect(jsonPath("$.days[1].routineCount").value(0))
                .andExpect(jsonPath("$.days[1].todoCount").value(0))
                .andExpect(jsonPath("$.days[1].routineCompletedCount").value(0))
                .andExpect(jsonPath("$.days[1].todoCompletedCount").value(0));
    }

    @Test
    void 월별_yearMonth_파라미터가_없으면_400() throws Exception {
        mockMvc.perform(get("/api/v1/calendar/month"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 월별_yearMonth_형식이_잘못되면_400() throws Exception {
        mockMvc.perform(get("/api/v1/calendar/month").param("yearMonth", "2026-8"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/calendar/month").param("yearMonth", "2026-08-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 인증된_사용자_id로_서비스를_호출한다() throws Exception {
        when(calendarService.day(eq(1L), eq(LocalDate.of(2026, 7, 1))))
                .thenReturn(new CalendarDayResponse(LocalDate.of(2026, 7, 1), List.of(),
                        new AgendaSummary(0, 0, 0.0)));

        mockMvc.perform(get("/api/v1/calendar").param("date", "2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "date": "2026-07-01",
                          "categories": [],
                          "summary": {
                            "completedCount": 0,
                            "remainingCount": 0,
                            "progressRate": 0.0
                          }
                        }
                        """, JsonCompareMode.STRICT))
                .andExpect(jsonPath("$.date").value("2026-07-01"))
                .andExpect(jsonPath("$.categories").isEmpty());
    }
}
