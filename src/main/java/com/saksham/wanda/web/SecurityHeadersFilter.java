package com.saksham.wanda.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Adds a few cheap browser-side protections to every response. */
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse out = (HttpServletResponse) res;
        out.setHeader("X-Content-Type-Options", "nosniff");   // never guess a file's type
        out.setHeader("X-Frame-Options", "DENY");            // the app cannot be embedded in another site
        chain.doFilter(req, res);
    }
}
