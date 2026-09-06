package com.proitbridge.lms.web;

import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.AuthService;
import com.proitbridge.lms.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService auth;
    private final PasswordResetService resets;
    private final CurrentUser current;

    public AuthController(AuthService auth, PasswordResetService resets, CurrentUser current) {
        this.auth = auth;
        this.resets = resets;
        this.current = current;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "up", "service", "pib-lms");
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest http) {
        String p = body.get("portal");
        AuthService.Portal portal = p == null || p.isBlank()
                ? null : AuthService.Portal.valueOf(p.toUpperCase());
        return auth.login(body.get("email"), body.get("password"), client(body, http), portal);
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me() {
        var me = current.get();
        return auth.me(me.id(), me.sessionId());
    }

    /* ------------------------------------------------------------ forgot password */

    /** Always the same answer, whether that address has an account or not. */
    @PostMapping("/auth/forgot")
    public Map<String, Object> forgot(@RequestBody Map<String, String> body, HttpServletRequest http) {
        return resets.request(body.get("email"), VideoController.clientIp(http));
    }

    /** Checked before the form is shown, so nobody types into a dead link. */
    @GetMapping("/auth/reset")
    public Map<String, Object> checkReset(@RequestParam String token) {
        return resets.check(token);
    }

    @PostMapping("/auth/reset")
    public Map<String, Object> completeReset(@RequestBody Map<String, String> body) {
        return resets.complete(body.get("token"), body.get("password"));
    }

    @PostMapping("/auth/logout")
    public Map<String, Object> logout() {
        auth.logout(current.get().sessionId());
        return Map.of("ok", true);
    }

    @PostMapping("/auth/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, String> body) {
        var me = current.get();
        return auth.changePassword(me.id(), me.sessionId(),
                body.get("currentPassword"), body.get("newPassword"));
    }

    private AuthService.Client client(Map<String, String> body, HttpServletRequest http) {
        return new AuthService.Client(
                body.get("deviceId"),
                body.getOrDefault("deviceLabel", "Unknown device"),
                VideoController.clientIp(http),
                http.getHeader("User-Agent"));
    }
}
