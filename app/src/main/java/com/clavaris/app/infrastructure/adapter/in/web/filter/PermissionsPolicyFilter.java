package com.clavaris.app.infrastructure.adapter.in.web.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * {@code Permissions-Policy} on every response, whichever filter chain serves it (found by an OWASP
 * ZAP baseline scan). No page of Clavaris uses the camera, microphone, location, payment or USB
 * APIs, so switching them off means an injected script or a framed third party cannot ask for them.
 * {@code publickey-credentials-get} and {@code publickey-credentials-create} stay on for this
 * origin because passkey sign-in and registration need them.
 *
 * <p>Sent from one filter ahead of the chains, like {@link ReferrerPolicyFilter}, so six chains do
 * not each need the same line.
 */
public final class PermissionsPolicyFilter implements Filter {

  public static final String HEADER = "Permissions-Policy";
  public static final String VALUE =
      "camera=(), microphone=(), geolocation=(), payment=(), usb=(), "
          + "publickey-credentials-get=(self), publickey-credentials-create=(self)";

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
