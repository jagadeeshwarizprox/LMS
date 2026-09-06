package io.jsonwebtoken;
import java.security.Key;
import java.util.Date;
public final class Jwts {
    public static JwtBuilder builder(){return null;}
    public static JwtParserBuilder parser(){return null;}
    public interface JwtBuilder {
        JwtBuilder subject(String s); JwtBuilder setSubject(String s);
        JwtBuilder issuer(String s); JwtBuilder id(String s);
        JwtBuilder claim(String name, Object value);
        JwtBuilder claims(java.util.Map<String,?> c);
        JwtBuilder issuedAt(Date d); JwtBuilder setIssuedAt(Date d);
        JwtBuilder expiration(Date d); JwtBuilder setExpiration(Date d);
        JwtBuilder notBefore(Date d);
        BuilderHeader header();
        JwtBuilder signWith(Key key);
        JwtBuilder signWith(Key key, Object alg);
        String compact();
    }
    public interface BuilderHeader {
        BuilderHeader add(String name, Object value);
        BuilderHeader keyId(String kid);
        BuilderHeader type(String t);
        JwtBuilder and();
    }
    public interface JwtParserBuilder {
        JwtParserBuilder verifyWith(javax.crypto.SecretKey key);
        JwtParserBuilder verifyWith(java.security.PublicKey key);
        JwtParserBuilder setSigningKey(Key key);
        JwtParserBuilder requireSubject(String s);
        JwtParser build();
    }
    public interface JwtParser {
        Jws<Claims> parseSignedClaims(CharSequence jwt);
        Jws<Claims> parseClaimsJws(String jwt);
    }
}
