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

    public JwtService(@Value("${lms.jwt.secret}") String secret,
                      @Value("${lms.jwt.ttl-minutes}") long ttlMinutes) {
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
