package org.springframework.security.core.context;
public class SecurityContextHolder {
    private static final ThreadLocal<SecurityContext> CTX = ThreadLocal.withInitial(SecurityContextHolder::createEmptyContext);
    public static SecurityContext getContext(){return CTX.get();}
    public static void setContext(SecurityContext c){CTX.set(c);}
    public static void clearContext(){CTX.remove();}
    public static SecurityContext createEmptyContext(){
        return new SecurityContext(){
            private org.springframework.security.core.Authentication a;
            public org.springframework.security.core.Authentication getAuthentication(){return a;}
            public void setAuthentication(org.springframework.security.core.Authentication x){a=x;}
        };
    }
}
