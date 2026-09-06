package org.springframework.security.core.authority;
import org.springframework.security.core.GrantedAuthority;
public class SimpleGrantedAuthority implements GrantedAuthority {
    private final String role; public SimpleGrantedAuthority(String role){this.role=role;}
    public String getAuthority(){return role;}
}
