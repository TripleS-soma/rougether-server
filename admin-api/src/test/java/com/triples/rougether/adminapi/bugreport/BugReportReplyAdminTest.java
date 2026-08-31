package com.triples.rougether.adminapi.bugreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.adminapi.bugreport.dto.AdminBugReportResponse;
import com.triples.rougether.adminapi.bugreport.service.BugReportAdminService;
import com.triples.rougether.domain.admin.entity.AdminUser;
import com.triples.rougether.domain.admin.repository.AdminUserRepository;
import com.triples.rougether.domain.bugreport.entity.BugReport;
import com.triples.rougether.domain.bugreport.entity.BugReportReply;
import com.triples.rougether.domain.bugreport.entity.BugReportStatus;
import com.triples.rougether.domain.bugreport.repository.BugReportReplyRepository;
import com.triples.rougether.domain.bugreport.repository.BugReportRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.notification.entity.Notification;
import com.triples.rougether.domain.notification.entity.NotificationType;
import com.triples.rougether.domain.notification.entity.PushStatus;
import com.triples.rougether.domain.notification.repository.NotificationRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

// 어드민 버그 제보 답장 (#348) - 등록·상태 동시 변경·검증·알림 저장 계약·목록 포함.
// push 발송(커밋 후)은 BugReportReplyPushFlowTest 가 실제 커밋 경계로 검증한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BugReportReplyAdminTest {

    @Autowired MockMvc mockMvc;
    @Autowired BugReportAdminService bugReportAdminService;
    @Autowired BugReportRepository bugReportRepository;
    @Autowired BugReportReplyRepository bugReportReplyRepository;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired UserRepository userRepository;
    @MockitoBean S3Client s3Client;

    private User user;
    private BugReport report;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.signUp("reply-bug@rougether.dev"));
        report = bugReportRepository.save(BugReport.submit(
                user, "완료 버튼 고장", "완료가 안 눌립니다", "1.0.0", "Android 14"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 답장_등록은_저장_알림_생성_응답_계약을_지킨다() throws Exception {
        mockMvc.perform(post("/admin/bug-reports/{id}/replies", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"확인했어요. 다음 버전에서 고칠게요.\"}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bugReportId").value(report.getId()))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.replies[0].content").value("확인했어요. 다음 버전에서 고칠게요."))
                .andExpect(jsonPath("$.replies[0].adminUsername").value("admin"))
                .andExpect(jsonPath("$.replies[0].createdAt").exists());

        List<BugReportReply> replies = bugReportReplyRepository
                .findWithAdminByBugReportIdIn(List.of(report.getId()));
        assertThat(replies).hasSize(1);
        assertThat(replies.getFirst().getAdminUser().getUsername()).isEqualTo("admin");

        Notification notification = findReplyNotification();
        assertThat(notification.getUser().getId()).isEqualTo(user.getId());
        assertThat(notification.getRefId()).isEqualTo(report.getId());
        assertThat(notification.getTitle()).isEqualTo("보내주신 제보에 답변이 도착했어요");
        assertThat(notification.getBody()).isEqualTo("확인했어요. 다음 버전에서 고칠게요.");
        // 커밋 전이라 push 는 아직 미발송 상태(발송은 AFTER_COMMIT 이후)
        assertThat(notification.getPushStatus()).isEqualTo(PushStatus.PENDING);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 답장하며_상태를_함께_바꿀_수_있다() throws Exception {
        mockMvc.perform(post("/admin/bug-reports/{id}/replies", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"고쳤어요\", \"status\": \"RESOLVED\"}").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        assertThat(bugReportRepository.findById(report.getId()).orElseThrow().getStatus())
                .isEqualTo(BugReportStatus.RESOLVED);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 긴_답장은_push_본문을_200자_미리보기로_자른다() throws Exception {
        String longContent = "가".repeat(300);
        mockMvc.perform(post("/admin/bug-reports/{id}/replies", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"" + longContent + "\"}").with(csrf()))
                .andExpect(status().isOk());

        Notification notification = findReplyNotification();
        assertThat(notification.getBody()).hasSize(200).endsWith("…");
        // 답장 원문은 자르지 않는다
        assertThat(bugReportReplyRepository.findByBugReportIdInOrderByIdAsc(List.of(report.getId()))
                .getFirst().getContent()).isEqualTo(longContent);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void 빈_내용_2001자_잘못된_상태는_400_없는_제보는_404() throws Exception {
        mockMvc.perform(post("/admin/bug-reports/{id}/replies", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"  \"}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUG_REPORT_REPLY_INVALID"));

        mockMvc.perform(post("/admin/bug-reports/{id}/replies", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"" + "가".repeat(2001) + "\"}").with(csrf()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/admin/bug-reports/{id}/replies", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"ok\", \"status\": \"NOPE\"}").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUG_REPORT_STATUS_INVALID"));

        mockMvc.perform(post("/admin/bug-reports/{id}/replies", 999_999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"ok\"}").with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(bugReportReplyRepository.findByBugReportIdInOrderByIdAsc(List.of(report.getId()))).isEmpty();
    }

    @Test
    void 목록_응답에_답장이_오래된_순으로_포함된다() {
        AdminUser admin = adminUserRepository.findByUsername("admin").orElseThrow();
        bugReportReplyRepository.save(BugReportReply.of(report, admin, "첫 답장"));
        bugReportReplyRepository.save(BugReportReply.of(report, admin, "두 번째 답장"));

        AdminBugReportResponse item = bugReportAdminService.getReports(null).stream()
                .filter(response -> response.bugReportId().equals(report.getId()))
                .findFirst().orElseThrow();
        assertThat(item.replies())
                .extracting(AdminBugReportResponse.AdminBugReportReplyResponse::content)
                .containsExactly("첫 답장", "두 번째 답장");
        assertThat(item.replies().getFirst().adminUsername()).isEqualTo("admin");
    }

    @Test
    void 미인증이면_답장_불가() throws Exception {
        mockMvc.perform(post("/admin/bug-reports/{id}/replies", report.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"ok\"}").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private Notification findReplyNotification() {
        List<Notification> notifications = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.BUG_REPORT_REPLY)
                .filter(n -> n.getUser().getId().equals(user.getId()))
                .toList();
        assertThat(notifications).hasSize(1);
        return notifications.getFirst();
    }
}
