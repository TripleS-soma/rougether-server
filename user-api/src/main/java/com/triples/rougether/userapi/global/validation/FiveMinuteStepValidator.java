package com.triples.rougether.userapi.global.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalTime;

public class FiveMinuteStepValidator implements ConstraintValidator<FiveMinuteStep, LocalTime> {

    @Override
    public boolean isValid(LocalTime value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return value.getMinute() % 5 == 0 && value.getSecond() == 0 && value.getNano() == 0;
    }
}
