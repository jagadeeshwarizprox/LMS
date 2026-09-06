package org.springframework.scheduling.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE,ElementType.METHOD,ElementType.FIELD,ElementType.PARAMETER,ElementType.ANNOTATION_TYPE})
public @interface Scheduled { String cron() default ""; long fixedDelay() default -1; String fixedDelayString() default ""; String fixedRateString() default ""; String initialDelayString() default ""; long fixedRate() default -1; long initialDelay() default -1; String zone() default ""; }
