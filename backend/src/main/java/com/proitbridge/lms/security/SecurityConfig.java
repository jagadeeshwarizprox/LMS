package com.proitbridge.lms.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${lms.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> c.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/forgot", "/api/auth/reset",
                                 "/api/health", "/api/public/faqs").permitAll()
                /* the check lists rooms and who can get in, so it is staff only, and it
                   has to sit above the join rule or the wildcard swallows it */
                .requestMatchers("/api/slots/join-check").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/video/**", "/api/faqs/**", "/api/slots/*/join").authenticated()
                .requestMatchers("/api/learner/**").hasRole("LEARNER")
                .requestMatchers("/api/mentor/**").hasAnyRole("MENTOR", "ADMIN", "SUPER_ADMIN")
                /* the shared week: every host sees it, and the controller decides per row
                   what they may change */
                .requestMatchers("/api/week/**").hasAnyRole("MENTOR", "ADMIN", "SUPER_ADMIN")
                /* analytics: scoped in the service, so a mentor sees their own learners */
                .requestMatchers("/api/analytics/**").hasAnyRole("MENTOR", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                /* the course is edited by both, so an admin is not blocked on one person
                   to fix a title. People, roles and toggles stay super admin only. */
                /*
                 * Attaching an assignment to a chapter is open to mentors as well.
                 *
                 * A mentor is the one who sets and marks the work, so making them raise a
                 * ticket to add a dataset is the wrong shape. The rest of chapter
                 * authoring stays with the office, because a chapter is shared content:
                 * rewriting its topics changes it for every cohort at once, including
                 * ones that have already been through it.
                 */
                .requestMatchers("/api/super/catalogue/chapters/*/assignment/**")
                    .hasAnyRole("MENTOR", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/super/catalogue/rubrics/**")
                    .hasAnyRole("MENTOR", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/super/catalogue/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                /* the track feature toggles: an admin decides what a track includes, so
                   they own this even though it sits under the super namespace */
                .requestMatchers("/api/super/features/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/super/features").hasAnyRole("ADMIN", "SUPER_ADMIN")
                .requestMatchers("/api/super/**").hasRole("SUPER_ADMIN")
                .anyRequest().authenticated())
            /*
             * Without this, Spring answers an unauthenticated request with 403, because
             * the anonymous authentication counts as "present but not allowed". The
             * client cannot tell that apart from a genuine permission problem, so a
             * learner whose session was replaced would sit there watching every request
             * fail with a token it never thought to discard. 401 is the honest answer,
             * and it is what tells the client to sign out.
             */
            .exceptionHandling(e -> e
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("X-Session-Ended"));
        cfg.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
