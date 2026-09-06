package org.springframework.http;
public enum HttpStatus {
    OK(200), CREATED(201), ACCEPTED(202), NO_CONTENT(204),
    MOVED_PERMANENTLY(301), FOUND(302), NOT_MODIFIED(304),
    BAD_REQUEST(400), UNAUTHORIZED(401), PAYMENT_REQUIRED(402), FORBIDDEN(403),
    NOT_FOUND(404), METHOD_NOT_ALLOWED(405), NOT_ACCEPTABLE(406), CONFLICT(409),
    GONE(410), PAYLOAD_TOO_LARGE(413), UNSUPPORTED_MEDIA_TYPE(415),
    UNPROCESSABLE_ENTITY(422), TOO_MANY_REQUESTS(429),
    INTERNAL_SERVER_ERROR(500), NOT_IMPLEMENTED(501), BAD_GATEWAY(502),
    SERVICE_UNAVAILABLE(503), GATEWAY_TIMEOUT(504);
    private final int code;
    HttpStatus(int c){this.code=c;}
    public int value(){return code;}
    public String getReasonPhrase(){return name();}
    public boolean is2xxSuccessful(){return code>=200&&code<300;}
    public boolean isError(){return code>=400;}
    public static HttpStatus valueOf(int c){ for(HttpStatus h:values()) if(h.code==c) return h; return INTERNAL_SERVER_ERROR; }
}
