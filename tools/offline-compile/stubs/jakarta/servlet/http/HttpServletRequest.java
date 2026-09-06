package jakarta.servlet.http;
import jakarta.servlet.ServletRequest;
import java.util.*;
public interface HttpServletRequest extends ServletRequest {
    String getHeader(String name);
    Enumeration<String> getHeaders(String name);
    String getMethod();
    String getRequestURI();
    String getServletPath();
    String getQueryString();
    String getContextPath();
    StringBuffer getRequestURL();
    Object getUserPrincipal();
    Cookie[] getCookies();
}
