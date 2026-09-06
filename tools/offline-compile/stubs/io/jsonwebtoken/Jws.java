package io.jsonwebtoken;
public interface Jws<T> { T getPayload(); T getBody(); }
