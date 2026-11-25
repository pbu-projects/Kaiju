package lol.pbu.kaiju.validation;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

@Introspected
public class ValidShiftLocationValidator implements ConstraintValidator<ValidShiftLocation, ShiftLocation> {

    /** Null inputs are left for @NotNull or @Body checks so this rule only checks location pairing. */
    @Override
    public boolean isValid(ShiftLocation value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return (value.isVirtual() && value.locationId() == null)
                || (!value.isVirtual() && value.locationId() != null);
    }
}
