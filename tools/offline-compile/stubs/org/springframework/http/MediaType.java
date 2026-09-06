package org.springframework.http;
public class MediaType {
    public static final MediaType APPLICATION_JSON=new MediaType("application/json");
    public static final MediaType APPLICATION_OCTET_STREAM=new MediaType("application/octet-stream");
    public static final MediaType TEXT_PLAIN=new MediaType("text/plain");
    public static final MediaType MULTIPART_FORM_DATA=new MediaType("multipart/form-data");
    public static final String APPLICATION_JSON_VALUE="application/json";
    private final String v; public MediaType(String v){this.v=v;}
    public static MediaType parseMediaType(String s){return new MediaType(s);}
    public String toString(){return v;}
}
