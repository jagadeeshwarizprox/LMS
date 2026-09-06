package org.springframework.security.config.annotation.web.builders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfigurationSource;

public class HttpSecurity {
    public interface CsrfSpec { CsrfSpec disable(); }
    public interface CorsSpec { CorsSpec configurationSource(CorsConfigurationSource s); CorsSpec disable(); }
    public interface SessionSpec { SessionSpec sessionCreationPolicy(SessionCreationPolicy p); }
    public interface AuthorizeSpec {
        MatcherSpec requestMatchers(String... patterns);
        MatcherSpec requestMatchers(HttpMethod method, String... patterns);
        MatcherSpec anyRequest();
    }
    public interface MatcherSpec extends AuthorizeSpec {
        AuthorizeSpec permitAll();
        AuthorizeSpec denyAll();
        AuthorizeSpec authenticated();
        AuthorizeSpec hasRole(String role);
        AuthorizeSpec hasAnyRole(String... roles);
        AuthorizeSpec hasAuthority(String a);
        AuthorizeSpec hasAnyAuthority(String... a);
    }
    public interface ExceptionSpec { ExceptionSpec authenticationEntryPoint(Object p); ExceptionSpec accessDeniedHandler(Object h); }
    public interface HttpBasicSpec { HttpBasicSpec disable(); }
    public interface FormLoginSpec { FormLoginSpec disable(); }
    public interface LogoutSpec { LogoutSpec disable(); }
    public interface HeadersSpec { HeadersSpec disable(); HeadersSpec frameOptions(Customizer<?> c); }

    public HttpSecurity csrf(Customizer<CsrfSpec> c){return this;}
    public HttpSecurity cors(Customizer<CorsSpec> c){return this;}
    public HttpSecurity sessionManagement(Customizer<SessionSpec> c){return this;}
    public HttpSecurity authorizeHttpRequests(Customizer<AuthorizeSpec> c){return this;}
    public HttpSecurity exceptionHandling(Customizer<ExceptionSpec> c){return this;}
    public HttpSecurity httpBasic(Customizer<HttpBasicSpec> c){return this;}
    public HttpSecurity formLogin(Customizer<FormLoginSpec> c){return this;}
    public HttpSecurity logout(Customizer<LogoutSpec> c){return this;}
    public HttpSecurity headers(Customizer<HeadersSpec> c){return this;}
    public HttpSecurity addFilterBefore(Object filter, Class<?> beforeFilter){return this;}
    public HttpSecurity addFilterAfter(Object filter, Class<?> afterFilter){return this;}
    public SecurityFilterChain build() throws Exception { return new SecurityFilterChain(){}; }
}
