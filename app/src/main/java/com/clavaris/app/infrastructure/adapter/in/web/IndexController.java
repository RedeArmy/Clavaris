package com.clavaris.app.infrastructure.adapter.in.web;

import com.clavaris.app.infrastructure.config.DefaultSecurityConfig;
import com.clavaris.identity.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
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
 * anyRequest().permitAll()} ) — no new security wiring needed.
 *
 * <p>Live bug fix, 2026-09-26: this page used to render with no model attributes at all — "Sign
 * in"/"Get started" always pointed an already-authenticated {@code PlatformAccount} straight back
 * at {@link com.clavaris.identity.infrastructure.adapter.in.web.PlatformLoginController}/{@link
 * com.clavaris.identity.infrastructure.adapter.in.web.RegisterPlatformAccountController}, both of
 * which now redirect away from themselves in that case (see each one's own identical fix) — but the
 * button still read "Sign in" until the click, a confusing "why is it asking me to log in when I
 * already am" moment. Deliberately kept as a redirect-free, still-static render rather than
 * redirecting this whole page to {@code /platform/dashboard} the moment a session exists: this
 * class's own Javadoc above already establishes the page as marketing/orientation, not an
 * authenticated surface an already-signed-in visitor is barred from revisiting — the one
 * conditional bit is swapping what the buttons do, not where this page itself sends them.
 */
@Controller
class IndexController {

  @SuppressWarnings("PMD.LongVariable")
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  /* package */ IndexController(
      @SuppressWarnings("PMD.LongVariable")
          final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping("/")
  /* package */ String index(final HttpServletRequest request, final Model model) {
    model.addAttribute("authenticated", currentPlatformAccount.resolve(request).isPresent());
    return "index";
  }
}
