package com.triples.rougether.userapi.invite.support;

import com.triples.rougether.domain.invite.repository.UserInviteCodeRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

// 개인 초대코드 발급: 영대문자+숫자 8자, 혼동문자(I,O,L,0,1) 제외.
// 집 초대코드(InviteCodeGenerator)와 같은 문자 집합을 쓰되 유일성은 user_invite_codes 안에서만 본다
// — 두 코드는 입력 화면이 달라 서로 겹쳐도 오인식되지 않는다.
@Component
public class UserInviteCodeGenerator {

    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_ATTEMPTS = 5;

    private final UserInviteCodeRepository userInviteCodeRepository;
    private final SecureRandom random = new SecureRandom();

    public UserInviteCodeGenerator(UserInviteCodeRepository userInviteCodeRepository) {
        this.userInviteCodeRepository = userInviteCodeRepository;
    }

    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (!userInviteCodeRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("초대코드 생성이 " + MAX_ATTEMPTS + "회 연속 충돌했습니다.");
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return builder.toString();
    }
}
