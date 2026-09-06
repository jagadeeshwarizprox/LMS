package com.proitbridge.lms.security;

/** The signed in person, read straight off the token. */
public record AuthUser(String id, String email, String role, String name, String sessionId) {
    public boolean isSuperAdmin() { return "SUPER_ADMIN".equals(role); }
}
