package com.triples.rougether.adminapi.attendance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.attendance.dto.AttendanceEventCreateResponse;
import com.triples.rougether.adminapi.attendance.service.AttendanceEventAdminService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AttendanceEventAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AttendanceEventAdminService attendanceEventAdminService;

    @Test
    void 관리자가_코인_보상표와_가구를_설정한다() throws Exception {
        when(attendanceEventAdminService.create(any())).thenReturn(new AttendanceEventCreateResponse(
                7L, "ATTENDANCE_10D_2026", "10일 연속 출석",
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 9, 14),
                10, 30, 5, 50, 42L));

        mockMvc.perform(post("/admin/attendance-events")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "ATTENDANCE_10D_2026",
                                  "title": "10일 연속 출석",
                                  "startsOn": "2026-08-16",
                                  "endsOn": "2026-09-14",
                                  "targetDays": 10,
                                  "dailyCoinAmount": 30,
                                  "bonusDay": 5,
                                  "bonusCoinAmount": 50,
                                  "rewardItemId": 42
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dailyCoinAmount").value(30))
                .andExpect(jsonPath("$.bonusDay").value(5))
                .andExpect(jsonPath("$.bonusCoinAmount").value(50))
                .andExpect(jsonPath("$.rewardItemId").value(42));
    }
}
