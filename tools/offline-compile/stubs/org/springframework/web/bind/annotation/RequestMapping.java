package org.springframework.web.bind.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE,ElementType.METHOD,ElementType.FIELD,ElementType.PARAMETER,ElementType.ANNOTATION_TYPE})
public @interface RequestMapping { String[] value() default {}; String[] path() default {}; String[] produces() default {}; String[] consumes() default {}; }
