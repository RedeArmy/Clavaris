package com.clavaris.app.infrastructure.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The bare root (`/`) — before this controller existed, hitting the deployed instance's own domain
 * with no path rendered Spring Boot's default Whitelabel error page (confirmed live, 2026-09-09):
 * Clavaris has no single global authenticated landing page by design (every real login lives
 * per-Organization, {@code /o/{organizationId}/login}, ADR-0010), so nothing ever claimed this
 * path. This is a marketing/orientation page, not an authenticated surface — the actual starting
 * point for a human (an operator evaluating or adopting Clavaris) to navigate from, linking to
 * {@code /platform/register}/{@code /platform/login} (RegisterPlatformAccountController/
 * PlatformLoginController's own real entry points), not a functional page in its own right.
 *
 * <p>Served by {@link DefaultSecurityConfig}'s own catch-all chain ({@code
 * anyRequest().permitAll()} ) — no new security wiring needed. Static content only, no model
 * attributes, no per-tenant branding (this page exists before any Organization/branding context
 * does).
 */
@Controller
class IndexController {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ IndexController() {
    // Intentionally empty — this class holds no state, only the @GetMapping method below.
  }

  @GetMapping("/")
  /* package */ String index() {
    return "index";
  }
}
