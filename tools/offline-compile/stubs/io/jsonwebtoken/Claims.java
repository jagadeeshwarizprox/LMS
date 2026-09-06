package io.jsonwebtoken;
import java.util.*;
public interface Claims extends Map<String,Object> {
    String getSubject(); String getIssuer(); String getId();
    Date getExpiration(); Date getIssuedAt(); Date getNotBefore();
    <T> T get(String key, Class<T> requiredType);
}
