package org.springframework.http;
public class ResponseEntity<T> {
    private final T body; private final HttpStatus status;
    public ResponseEntity(T body, HttpStatus status){this.body=body;this.status=status;}
    public T getBody(){return body;}
    public HttpStatus getStatusCode(){return status;}
    public static <T> ResponseEntity<T> ok(T body){return new ResponseEntity<>(body,HttpStatus.OK);}
    public static Builder ok(){return new Builder(HttpStatus.OK);}
    public static Builder status(HttpStatus s){return new Builder(s);}
    public static Builder status(int s){return new Builder(HttpStatus.valueOf(s));}
    public static Builder created(java.net.URI u){return new Builder(HttpStatus.CREATED);}
    public static Builder noContent(){return new Builder(HttpStatus.NO_CONTENT);}
    public static Builder badRequest(){return new Builder(HttpStatus.BAD_REQUEST);}
    public static <T> Builder notFound(){return new Builder(HttpStatus.NOT_FOUND);}
    public static class Builder {
        private final HttpStatus s; Builder(HttpStatus s){this.s=s;}
        public Builder header(String name, String... values){return this;}
        public Builder headers(HttpHeaders h){return this;}
        public Builder contentType(MediaType t){return this;}
        public Builder contentLength(long l){return this;}
        public Builder cacheControl(Object c){return this;}
        public <T> ResponseEntity<T> body(T body){return new ResponseEntity<>(body,s);}
        public <T> ResponseEntity<T> build(){return new ResponseEntity<>(null,s);}
    }
}
