package com.triples.rougether.adminapi.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
import com.triples.rougether.domain.member.repository.UserWalletRepository;
import com.triples.rougether.domain.shared.CurrencyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WalletGrantTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    UserWalletRepository userWalletRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    void 지갑이_없으면_발급하고_적립하며_재지급은_누적된다() throws Exception {
        User user = userRepository.save(User.signUp("grant-target@rougether.dev"));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/wallets/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyType\": \"DIAMOND\", \"amount\": 500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(500))
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/wallets/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyType\": \"DIAMOND\", \"amount\": 250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(750))
                .andExpect(jsonPath("$.created").value(false));

        int balance = userWalletRepository
                .findByUserIdAndCurrencyType(user.getId(), CurrencyType.DIAMOND)
                .orElseThrow().getBalance();
        assertThat(balance).isEqualTo(750);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 없는_회원이면_404() throws Exception {
        mockMvc.perform(post("/admin/users/999999/wallets/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyType\": \"COIN\", \"amount\": 100}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 지급액이_0_이하이면_400() throws Exception {
        User user = userRepository.save(User.signUp("grant-invalid@rougether.dev"));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/wallets/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyType\": \"COIN\", \"amount\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 어드민_인증_없이는_호출할_수_없다() throws Exception {
        mockMvc.perform(post("/admin/users/1/wallets/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyType\": \"COIN\", \"amount\": 100}"))
                .andExpect(status().is3xxRedirection());
    }
}
