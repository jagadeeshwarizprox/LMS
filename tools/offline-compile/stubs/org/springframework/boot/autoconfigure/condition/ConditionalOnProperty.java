package org.springframework.boot.autoconfigure.condition;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE,ElementType.METHOD,ElementType.FIELD,ElementType.PARAMETER,ElementType.ANNOTATION_TYPE})
public @interface ConditionalOnProperty { String name() default ""; String prefix() default ""; String havingValue() default ""; boolean matchIfMissing() default false; }
