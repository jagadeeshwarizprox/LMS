package org.springframework.security.authentication;
import org.springframework.security.core.*;
import java.util.*;
public class UsernamePasswordAuthenticationToken implements Authentication {
    private final Object principal, credentials; private final Collection<? extends GrantedAuthority> auths;
    private Object details;
    public UsernamePasswordAuthenticationToken(Object p,Object c){this(p,c,new ArrayList<>());}
    public UsernamePasswordAuthenticationToken(Object p,Object c,Collection<? extends GrantedAuthority> a){principal=p;credentials=c;auths=a;}
    public Object getPrincipal(){return principal;}
    public Object getCredentials(){return credentials;}
    public Collection<? extends GrantedAuthority> getAuthorities(){return auths;}
    public boolean isAuthenticated(){return true;}
    public String getName(){return String.valueOf(principal);}
    public void setDetails(Object d){this.details=d;}
    public Object getDetails(){return details;}
}
