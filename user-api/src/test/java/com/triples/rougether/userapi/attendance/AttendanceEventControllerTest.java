package com.triples.rougether.userapi.attendance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.userapi.attendance.dto.AttendanceCheckInResponse;
import com.triples.rougether.userapi.attendance.dto.AttendanceEventStatusResponse;
import com.triples.rougether.userapi.attendance.dto.AttendanceEventStatusResponse.DailyReward;
import com.triples.rougether.userapi.attendance.dto.AttendanceEventStatusResponse.Reward;
import com.triples.rougether.userapi.attendance.service.AttendanceEventService;
import com.triples.rougether.userapi.attendance.web.AttendanceEventController;
import com.triples.rougether.userapi.auth.service.TokenService;
import com.triples.rougether.userapi.global.security.AuthUser;
import com.triples.rougether.userapi.global.security.CurrentUserArgumentResolver;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AttendanceEventController.class)
@AutoConfigureMockMvc(addFilters = false)
class AttendanceEventControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AttendanceEventService attendanceEventService;
    @MockitoBean private CurrentUserArgumentResolver currentUserArgumentResolver;
    @MockitoBean private TokenService tokenService;

    @BeforeEach
    void stubCurrentUser() throws Exception {
        when(currentUserArgumentResolver.supportsParameter(any())).thenReturn(true);
        when(currentUserArgumentResolver.resolveArgument(any(), any(), any(), any()))
                .thenReturn(new AuthUser(1L, null));
    }

    @Test
    void 현재_출석_이벤트_상태_응답_계약() throws Exception {
        when(attendanceEventService.getStatus(1L)).thenReturn(statusResponse());

        mockMvc.perform(get("/api/v1/events/attendance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyRewards[4].coinAmount").value(50))
                .andExpect(jsonPath("$.dailyRewards[9].coinAmount").value(30))
                .andExpect(jsonPath("$.dailyRewards[9].furnitureReward").value(true))
                .andExpect(jsonPath("$.reward.itemId").value(42));
    }

    @Test
    void 오늘_출석_체크_응답_계약() throws Exception {
        when(attendanceEventService.checkIn(1L))
                .thenReturn(new AttendanceCheckInResponse(true, 30, 190, false, statusResponse()));

        mockMvc.perform(post("/api/v1/events/attendance/check-ins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newCheckIn").value(true))
                .andExpect(jsonPath("$.coinRewardAmount").value(30))
                .andExpect(jsonPath("$.coinBalance").value(190))
                .andExpect(jsonPath("$.rewardGrantedNow").value(false));
    }

    private AttendanceEventStatusResponse statusResponse() {
        return new AttendanceEventStatusResponse(
                7L, "ATTENDANCE_10D_2026", "10일 연속 출석",
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 14),
                10, 3, true, false,
                List.of(LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 18)),
                List.of(
                        new DailyReward(1, 30, false, true), new DailyReward(2, 30, false, true),
                        new DailyReward(3, 30, false, true), new DailyReward(4, 30, false, false),
                        new DailyReward(5, 50, false, false), new DailyReward(6, 30, false, false),
                        new DailyReward(7, 30, false, false), new DailyReward(8, 30, false, false),
                        new DailyReward(9, 30, false, false), new DailyReward(10, 30, true, false)),
                new Reward(42L, "10일 출석 기념 트로피",
                        "items/events/attendance-10-day-trophy.png", null, false));
    }
}
