package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Answers 400, not 500, for a request the servlet container cannot even read (found by a
 * Schemathesis run against the management API: 21 of 1344 generated requests got a 500 and an error
 * line in the log). Two shapes:
 *
 * <ul>
 *   <li>a query string Tomcat refuses to split into parameters, such as {@code ?=x} or an empty
 *       name. The first filter or interceptor that reads any parameter (the language switch reads
 *       {@code lang} on every request) made the container throw, and nothing that far out in the
 *       filter chain turns that into anything but a 500;
 *   <li>{@code multipart/form-data} with no {@code boundary}, which the container rejects the same
 *       way on first parameter access.
 * </ul>
 *
 * Nobody did anything wrong that the server should alert on, so these are client errors. A request
 * whose body is a form is not read here, only its media type is checked: parsing a body before
 * authentication is what the security chain decides, not this filter.
 */
public final class MalformedRequestFilter implements Filter {

  @Override
  public void doFilter(
      final ServletRequest request, final ServletResponse response, final FilterChain chain)
      throws IOException, ServletException {
    if (request instanceof HttpServletRequest http
        && response instanceof HttpServletResponse out
        && isMalformed(http)) {
      out.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      out.setContentType("application/problem+json");
      out.setCharacterEncoding(StandardCharsets.UTF_8.name());
      out.getWriter()
          .write(
              "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,"
                  + "\"detail\":\"The request could not be read\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean isMalformed(final HttpServletRequest request) {
    final String contentType = request.getContentType();
    final String media = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    if (media.startsWith("multipart/")) {
      return !media.contains("boundary=");
    }
    if (media.startsWith("application/x-www-form-urlencoded")) {
      return false;
    }
    if (request.getQueryString() == null) {
      return false;
    }
    try {
      request.getParameterMap();
      return false;
    } catch (RuntimeException unreadable) {
      return true;
    }
  }
}
