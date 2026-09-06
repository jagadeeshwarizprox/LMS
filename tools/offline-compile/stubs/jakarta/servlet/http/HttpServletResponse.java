package jakarta.servlet.http;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
public interface HttpServletResponse extends ServletResponse {
    void setStatus(int sc);
    void sendError(int sc) throws IOException;
    void sendError(int sc,String msg) throws IOException;
    void setHeader(String name,String value);
    void addHeader(String name,String value);
    void addCookie(Cookie c);
}
