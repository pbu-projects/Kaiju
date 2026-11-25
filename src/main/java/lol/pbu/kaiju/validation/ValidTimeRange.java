package lol.pbu.kaiju.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = ValidTimeRangeValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ValidTimeRange {

    String message() default "Shift start time must be before end time.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
