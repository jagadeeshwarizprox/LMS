package org.springframework.web.server;
import org.springframework.http.HttpStatus;
public class ResponseStatusException extends RuntimeException {
    private final HttpStatus status; private final String reason;
    public ResponseStatusException(HttpStatus status){this(status,null);}
    public ResponseStatusException(HttpStatus status,String reason){super(reason);this.status=status;this.reason=reason;}
    public ResponseStatusException(HttpStatus status,String reason,Throwable c){super(reason,c);this.status=status;this.reason=reason;}
    public HttpStatus getStatusCode(){return status;}
    public String getReason(){return reason;}
}
