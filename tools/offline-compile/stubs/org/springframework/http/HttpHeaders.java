package org.springframework.http;
import java.util.*;
public class HttpHeaders {
    public static final String CONTENT_DISPOSITION="Content-Disposition";
    public static final String CONTENT_TYPE="Content-Type";
    public static final String CONTENT_LENGTH="Content-Length";
    public static final String AUTHORIZATION="Authorization";
    public static final String CACHE_CONTROL="Cache-Control";
    public void add(String k,String v){}
    public void set(String k,String v){}
    public String getFirst(String k){return null;}
}
