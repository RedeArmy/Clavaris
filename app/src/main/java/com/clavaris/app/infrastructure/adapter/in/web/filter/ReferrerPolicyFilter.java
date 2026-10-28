package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * TD-SEC-071: {@code Referrer-Policy: no-referrer} on every response, whichever filter chain serves
 * it. Several single-use tokens (email verification, password reset, sign-in links, the
 * email-change confirmation) travel in the URL query; without this header a page that links to or
 * loads anything off-origin would send the full URL, token included, in {@code Referer}. Spring
 * Security's default headers do not include it, and six chains would each need the same line, so it
 * is one filter ahead of all of them.
 *
 * <p>Nothing in the sign-in, consent or token flows reads {@code Referer} (CSRF protection checks
 * the token and the {@code Origin} header, not {@code Referer}), so the strictest policy costs
 * nothing.
 */
@SuppressWarnings("PMD.AtLeastOneConstructor")
public final class ReferrerPolicyFilter implements Filter {

  public static final String HEADER = "Referrer-Policy";
  public static final String VALUE = "no-referrer";

  @Override
  public void doFilter(
      final ServletRequest request, final ServletResponse response, final FilterChain chain)
      throws IOException, ServletException {
    if (response instanceof HttpServletResponse http) {
      http.setHeader(HEADER, VALUE);
    }
    chain.doFilter(request, response);
  }
}
