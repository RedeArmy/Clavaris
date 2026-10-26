package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * TD-PERF-045: lets a verifier and any shared cache reuse a JWKS response for {@code maxAgeSeconds}
 * instead of asking Postgres on every poll. The endpoint is unauthenticated, polled by every
 * resource server, and each request costs one indexed query (TD-PERF-035 removed the disk and
 * crypto work), so the header removes most of that load without touching the endpoint.
 *
 * <p><b>The one thing a cache here can break, and why {@code maxAgeSeconds} is short:</b> a
 * verifier that honours {@code max-age} and does not refetch on an unknown {@code kid} will reject
 * a token signed by a key published less than {@code maxAgeSeconds} ago. Rotation publishes the new
 * key and starts signing with it at once, and keeps the previous key published through the overlap
 * window, so the old {@code kid} is never the problem; the new one is, for at most this long after
 * a rotation. The default is 60 seconds, which a manual, audited rotation (ADR-0010 section 5) can
 * wait out, and most verifier libraries refetch on a {@code kid} miss anyway. A value of 0 turns
 * the header off and leaves the framework's {@code no-store}.
 *
 * <p>Only a 200 carries it: an error response (an unknown Organization, a failure) must never be
 * cached as if it were the key set, so any other status is marked {@code no-store}. Spring
 * Security's own cache-control writer leaves a response that already has the header alone, which is
 * why this runs as a servlet filter ahead of it and sets the header up front. Only the two JWKS
 * paths are touched; every other request passes through.
 */
public final class JwksCacheControlFilter implements Filter {

  // A servlet url-pattern cannot say "/o/<anything>/oauth2/jwks" (a wildcard may only lead or
  // trail), so the filter is mapped to everything and recognises the two paths itself.
  private static final Pattern JWKS_PATH = Pattern.compile("^(/o/[^/]+)?/oauth2/jwks$");
  private static final String CACHE_CONTROL = "Cache-Control";
  private static final String NO_STORE = "no-store";

  private final String publicCacheControl;

  public JwksCacheControlFilter(final int maxAgeSeconds) {
    this.publicCacheControl = "public, max-age=" + maxAgeSeconds;
  }

  @Override
  public void doFilter(
      final ServletRequest request, final ServletResponse response, final FilterChain chain)
      throws IOException, ServletException {
    if (request instanceof HttpServletRequest http
        && "GET".equals(http.getMethod())
        && JWKS_PATH.matcher(pathWithinApplication(http)).matches()
        && response instanceof HttpServletResponse httpResponse) {
      httpResponse.setHeader(CACHE_CONTROL, publicCacheControl);
      chain.doFilter(request, new OnlySuccessIsCacheable(httpResponse));
    } else {
      chain.doFilter(request, response);
    }
  }

  // Read after the custom-domain rewrite (this filter is ordered just behind it), so a tenant's
  // /oauth2/jwks on its own domain is seen as /o/{id}/oauth2/jwks.
  private static String pathWithinApplication(final HttpServletRequest request) {
    final String uri = request.getRequestURI();
    final String context = request.getContextPath();
    return context.isEmpty() ? uri : uri.substring(context.length());
  }

  // A non-200 outcome withdraws the public header before the response is committed.
  private static final class OnlySuccessIsCacheable extends HttpServletResponseWrapper {

    private OnlySuccessIsCacheable(final HttpServletResponse response) {
      super(response);
    }

    @Override
    public void setStatus(final int status) {
      withdrawUnlessOk(status);
      super.setStatus(status);
    }

    @Override
    public void sendError(final int status) throws IOException {
      withdrawUnlessOk(status);
      super.sendError(status);
    }

    @Override
    public void sendError(final int status, final String message) throws IOException {
      withdrawUnlessOk(status);
      super.sendError(status, message);
    }

    private void withdrawUnlessOk(final int status) {
      if (status != SC_OK) {
        setHeader(CACHE_CONTROL, NO_STORE);
      }
    }
  }
}
