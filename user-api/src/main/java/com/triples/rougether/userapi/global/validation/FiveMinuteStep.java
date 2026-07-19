package com.triples.rougether.userapi.global.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 시각 입력을 5분 단위(분이 5의 배수, 초 이하 0)로 제한함. 프론트 시간 picker의 5분 step과 계약을 맞춤.
// null 허용 여부는 이 제약이 아니라 필드별 @NotNull 로 다룸.
@Documented
@Constraint(validatedBy = FiveMinuteStepValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface FiveMinuteStep {

    String message() default "시각은 5분 단위(HH:mm, 분은 5의 배수)여야 합니다";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
