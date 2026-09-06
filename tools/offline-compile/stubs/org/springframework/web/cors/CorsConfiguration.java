package org.springframework.web.cors;
import java.util.*;
public class CorsConfiguration {
    public void setAllowedOrigins(List<String> o){}
    public void setAllowedOriginPatterns(List<String> o){}
    public void setAllowedMethods(List<String> m){}
    public void setAllowedHeaders(List<String> h){}
    public void setExposedHeaders(List<String> h){}
    public void setAllowCredentials(Boolean b){}
    public void setMaxAge(Long s){}
    public void addAllowedOrigin(String o){}
}
