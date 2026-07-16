package com.triples.rougether.adminapi.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.triples.rougether.domain.character.entity.Character;
import com.triples.rougether.domain.character.entity.UserCharacter;
import com.triples.rougether.domain.character.repository.CharacterRepository;
import com.triples.rougether.domain.character.repository.UserCharacterRepository;
import com.triples.rougether.domain.member.entity.User;
import com.triples.rougether.domain.member.repository.UserRepository;
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
class CharacterGrantTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CharacterRepository characterRepository;

    @Autowired
    UserCharacterRepository userCharacterRepository;

    private Character character(String code, boolean active) {
        return characterRepository.save(new Character(code, code, "characters/" + code + ".png", 10, active));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 첫_캐릭터는_착용까지_지급되고_재지급은_멱등이다() throws Exception {
        User user = userRepository.save(User.signUp("char-grant@rougether.dev"));
        character("cg_bear", true);

        mockMvc.perform(post("/admin/users/" + user.getId() + "/characters/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterCode\": \"cg_bear\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyOwned").value(false))
                .andExpect(jsonPath("$.selected").value(true));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/characters/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterCode\": \"cg_bear\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyOwned").value(true));

        assertThat(userCharacterRepository.findByUserIdAndDeletedAtIsNull(user.getId())).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 두_번째_캐릭터는_착용하지_않고_지급된다() throws Exception {
        User user = userRepository.save(User.signUp("char-grant-2nd@rougether.dev"));
        Character worn = character("cg_worn", true);
        character("cg_next", true);
        userCharacterRepository.save(UserCharacter.createSelected(user, worn));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/characters/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterCode\": \"cg_next\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(false));

        // 기존 착용은 유지된다 (지급이 착용을 바꾸지 않음)
        assertThat(userCharacterRepository.findByUserIdAndSelectedTrueAndDeletedAtIsNull(user.getId())
                .orElseThrow().getCharacter().getCode()).isEqualTo("cg_worn");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 없는_회원이면_404_없는_코드나_비활성이면_400() throws Exception {
        User user = userRepository.save(User.signUp("char-grant-err@rougether.dev"));
        character("cg_retired", false);

        mockMvc.perform(post("/admin/users/999999/characters/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterCode\": \"cg_retired\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/characters/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterCode\": \"no_such_code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_GRANT_INVALID"));

        mockMvc.perform(post("/admin/users/" + user.getId() + "/characters/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterCode\": \"cg_retired\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CHARACTER_GRANT_INVALID"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void 캐릭터_옵션_목록은_활성만_반환한다() throws Exception {
        character("cg_opt_active", true);
        character("cg_opt_retired", false);

        mockMvc.perform(get("/admin/characters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'cg_opt_active')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'cg_opt_retired')]").doesNotExist());
    }

    @Test
    void 어드민_인증_없이는_호출할_수_없다() throws Exception {
        mockMvc.perform(post("/admin/users/1/characters/grant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"characterCode\": \"bear\"}"))
                .andExpect(status().is3xxRedirection());
    }
}
