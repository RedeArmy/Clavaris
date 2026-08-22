package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * {@link AuthenticatedSessionEstablisher}'s platform-tier sibling — same "identity-module never
 * depends on spring-security-config, the bridge lives in app" rationale, and a deliberately
 * separate port rather than reusing that one: a {@code PlatformAccount}'s authenticated session and
 * a tenant {@code Account}'s are two different security realms (ADR-0012), never blurred behind a
 * shared type even where the mechanics are identical.
 *
 * <p>Known limitation (ADR-0012, not yet solved): a single {@code HttpSession} holds one {@code
 * SecurityContext} — logging into the platform dashboard while a tenant login is also active in the
 * same browser session (or vice versa) overwrites the earlier one. Not a security hole (each
 * request is still checked against whichever context is actually current), but a real UX gap for
 * the rare case of one person acting as both their own end-user and their own platform owner in one
 * browser tab.
 */
@FunctionalInterface
public interface PlatformAuthenticatedSessionEstablisher {

  String establish(
      HttpServletRequest request,
      HttpServletResponse response,
      UUID platformAccountId,
      String fallbackUrl);
}
