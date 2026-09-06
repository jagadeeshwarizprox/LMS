package org.springframework.security.crypto.password;
public interface PasswordEncoder { String encode(CharSequence raw); boolean matches(CharSequence raw,String encoded); }
