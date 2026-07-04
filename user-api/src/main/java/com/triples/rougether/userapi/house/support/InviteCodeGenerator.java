package com.triples.rougether.userapi.house.support;

import com.triples.rougether.domain.house.repository.HouseRepository;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

// 집 초대코드 발급: 영대문자+숫자 8자, 혼동문자(I,O,L,0,1) 제외.
// uq_house_invite(UNIQUE) 충돌은 사전 존재 검사 + 재시도로 회피한다.
@Component
public class InviteCodeGenerator {

    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final int MAX_ATTEMPTS = 5;

    private final HouseRepository houseRepository;
    private final SecureRandom random = new SecureRandom();

    public InviteCodeGenerator(HouseRepository houseRepository) {
        this.houseRepository = houseRepository;
    }

    public String generate() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (!houseRepository.existsByInviteCode(code)) {
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
