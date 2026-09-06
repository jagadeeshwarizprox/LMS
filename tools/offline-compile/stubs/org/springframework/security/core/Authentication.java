package org.springframework.security.core;
import java.util.*;
public interface Authentication { Object getPrincipal(); Object getCredentials(); Collection<? extends GrantedAuthority> getAuthorities(); boolean isAuthenticated(); String getName(); }
