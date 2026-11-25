package lol.pbu.kaiju.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Documented
@Constraint(validatedBy = ValidShiftLocationValidator.class)
@Target(TYPE)
@Retention(RUNTIME)
public @interface ValidShiftLocation {

    String message() default "A shift must have a location if it is not virtual, and must not have a location if it is virtual.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
