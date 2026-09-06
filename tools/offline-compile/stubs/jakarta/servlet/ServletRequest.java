package jakarta.servlet;
public interface ServletRequest { Object getAttribute(String n); void setAttribute(String n,Object v); String getRemoteAddr(); }
