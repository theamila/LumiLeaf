package com.lumileaf.lumi.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class NoCacheFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI();

        boolean isPublic = path.startsWith("/trace/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.equals("/login")
                || path.startsWith("/lumbini_logo.png")
                || path.startsWith("/style.css");

        if (!isPublic) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
            response.setHeader("Pragma", "no-cache");
            response.setDateHeader("Expires", 0);
        }

        chain.doFilter(req, res);
    }
}