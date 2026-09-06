package org.springframework.web.bind.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE,ElementType.METHOD,ElementType.FIELD,ElementType.PARAMETER,ElementType.ANNOTATION_TYPE})
public @interface ResponseStatus { org.springframework.http.HttpStatus value() default org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR; String reason() default ""; }
