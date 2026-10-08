package com.proitbridge.lms.security;

import com.proitbridge.lms.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMillis;

    /** The values application.yml and docker-compose.yml fall back to when JWT_SECRET is not set. */
    static final java.util.Set<String> DEV_SECRETS = java.util.Set.of(
            "pib-lms-development-secret-key-change-me-please-32b",
            "change-me-to-a-real-secret-at-least-32-chars");

    public JwtService(@Value("${lms.jwt.secret}") String secret,
                      @Value("${lms.jwt.ttl-minutes}") long ttlMinutes,
                      @Value("${lms.app.base-url:http://localhost:5173}") String baseUrl) {
        /*
         * The development secret is in the repository, so anybody who has read it can sign
         * a super admin token for a server still using it. Locally that does not matter.
         * Anywhere else it is an open door, so a server whose public address is not
         * localhost refuses to start until a real secret is set.
         */
        boolean local = baseUrl == null || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1");
        if (DEV_SECRETS.contains(secret) && !local) {
            throw new IllegalStateException(
                    "JWT_SECRET is still the development default, and LMS_BASE_URL (" + baseUrl
                    + ") is not localhost. Set JWT_SECRET to a private value before going live, "
                    + "for example the output of: openssl rand -base64 48");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        /*
         * HS256 needs at least 256 bits of key. The library throws for a shorter one,
         * but its message says nothing about which setting is wrong, and this fails at
         * startup where somebody is already guessing.
         */
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET is " + bytes.length + " bytes. It must be at least 32. "
                    + "Set a longer value, for example the output of: openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlMillis = ttlMinutes * 60_000L;
    }

    public String issue(User user, String sessionId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getId())
                .claim("sid", sessionId)
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("name", user.getFullName())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMillis))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
