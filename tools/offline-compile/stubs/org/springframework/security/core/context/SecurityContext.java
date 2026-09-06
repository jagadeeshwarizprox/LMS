package org.springframework.security.core.context;
import org.springframework.security.core.Authentication;
public interface SecurityContext { Authentication getAuthentication(); void setAuthentication(Authentication a); }
