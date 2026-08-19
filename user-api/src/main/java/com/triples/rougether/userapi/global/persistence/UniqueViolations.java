package com.triples.rougether.userapi.global.persistence;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

// 무결성 위반 예외가 "특정 unique 제약" 위반인지 판정. 하이버네이트가 제약 이름을 뽑아준 경우 그것으로,
// 아니면 드라이버 메시지("Duplicate entry ... for key 'todos.uk_...'")로 본다.
// 사전 조회를 통과한 동시 요청이 unique 를 깰 때만 409 로 변환하고, 그 외 무결성 오류(길이 초과 등)는 위장하지 않기 위한 헬퍼.
public final class UniqueViolations {

    private UniqueViolations() {
    }

    public static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
        String needle = constraintName.toLowerCase(Locale.ROOT);
        if (e.getCause() instanceof ConstraintViolationException ce && ce.getConstraintName() != null) {
            return ce.getConstraintName().toLowerCase(Locale.ROOT).contains(needle);
        }
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(needle);
    }
}
