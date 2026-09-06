package com.proitbridge.lms.service.video;

import com.proitbridge.lms.domain.Video;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Date;
import java.util.Base64;
import java.util.Map;

/**
 * Signed playback. The token expires in two minutes and Cloudflare refuses it
 * outside the allowed origins, so a forwarded link is dead on arrival.
 *
 * Written and ready. Switch it on with VIDEO_PROVIDER=CLOUDFLARE_STREAM once the
 * content is uploaded; nothing else in the application changes.
 */
@Component
public class CloudflareStreamProvider implements VideoProvider {

    private final String customerCode;
    private final String keyId;
    private final String pemBase64;

    public CloudflareStreamProvider(
            @Value("${lms.video.cloudflare.customer-code:}") String customerCode,
            @Value("${lms.video.cloudflare.key-id:}") String keyId,
            @Value("${lms.video.cloudflare.private-key:}") String pemBase64) {
        this.customerCode = customerCode;
        this.keyId = keyId;
        this.pemBase64 = pemBase64;
    }

    @Override
    public String key() { return "CLOUDFLARE_STREAM"; }

    @Override
    public Grant grant(Video video, String viewerFingerprint) {
        if (keyId.isBlank() || pemBase64.isBlank()) {
            throw new IllegalStateException(
                    "Cloudflare Stream is selected but no signing key is configured.");
        }
        Instant now = Instant.now();
        String token = Jwts.builder()
                .header().add("kid", keyId).and()
                .claim("sub", video.getExternalId())
                .claim("kid", keyId)
                /* typed setters: exp and nbf are reserved, and passing them as raw
                   numbers through claim() relies on the library coercing them */
                .expiration(Date.from(now.plusSeconds(120)))
                .notBefore(Date.from(now.minusSeconds(10)))
                .claim("accessRules", java.util.List.of(
                        Map.of("type", "any", "action", "allow")))
                .signWith(privateKey())
                .compact();
        String url = "https://customer-" + customerCode + ".cloudflarestream.com/" + token + "/iframe";
        return new Grant("CLOUDFLARE_STREAM", null, url, 120, true);
    }

    private PrivateKey privateKey() {
        try {
            byte[] der = Base64.getDecoder().decode(pemBase64);
            String pem = new String(der)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        } catch (Exception e) {
            throw new IllegalStateException("Cloudflare signing key could not be read.", e);
        }
    }
}
