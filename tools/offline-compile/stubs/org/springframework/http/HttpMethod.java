package org.springframework.http;
public final class HttpMethod {
    public static final HttpMethod GET=new HttpMethod("GET"), POST=new HttpMethod("POST"),
        PUT=new HttpMethod("PUT"), PATCH=new HttpMethod("PATCH"), DELETE=new HttpMethod("DELETE"),
        OPTIONS=new HttpMethod("OPTIONS"), HEAD=new HttpMethod("HEAD");
    private final String n; private HttpMethod(String n){this.n=n;}
    public String name(){return n;}
    public static HttpMethod valueOf(String s){return new HttpMethod(s);}
}
