package org.springframework.web.filter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
public abstract class OncePerRequestFilter implements Filter {
    protected abstract void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException;
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws ServletException, IOException {
        doFilterInternal((HttpServletRequest) req,(HttpServletResponse) res, chain);
    }
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException { return false; }
}
