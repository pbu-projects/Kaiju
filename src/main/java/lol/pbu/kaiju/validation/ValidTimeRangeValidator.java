package lol.pbu.kaiju.validation;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

@Introspected
public class ValidTimeRangeValidator implements ConstraintValidator<ValidTimeRange, ShiftTimeRange> {

    @Override
    public boolean isValid(ShiftTimeRange value, ConstraintValidatorContext context) {
        if (value == null || value.startTime() == null || value.endTime() == null) {
            return true;
        }
        return value.startTime().isBefore(value.endTime());
    }
}
