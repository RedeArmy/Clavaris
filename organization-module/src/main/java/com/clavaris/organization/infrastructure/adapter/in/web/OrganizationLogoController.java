package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.getorganizationlogo.GetOrganizationLogoUseCase;
import com.clavaris.organization.domain.model.OrganizationLogo;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves an Organization's logo to the application's own people, from the platform's own origin:
 * the sign-in and consent pages draw it with an ordinary {@code <img>}, which their content
 * security policy ({@code img-src 'self'}) allows only for the same origin.
 *
 * <p>Public, like the avatar endpoint: a browser's {@code <img src>} carries no credentials, and
 * the logo is meant to be seen by anyone who reaches the sign-in page. Because it is public and
 * user-supplied, the response is locked down: {@code nosniff} so the browser never guesses a
 * different type, and a policy that forbids everything, so even a file that somehow were not a
 * plain image could run nothing. (The image types accepted are checked on upload; SVG is not one.)
 */
@Controller
public class OrganizationLogoController {

  // The URL carries the time the logo was last set (?v=), so a changed logo has a new URL and the
  // old one can be cached for as long as a browser will keep it. A request without it gets a short
  // life instead, so a stale copy cannot outlive a replacement for long.
  private static final Duration LONG_MAX_AGE = Duration.ofDays(30);
  private static final Duration SHORT_MAX_AGE = Duration.ofMinutes(5);

  private final GetOrganizationLogoUseCase getLogo;

  public OrganizationLogoController(final GetOrganizationLogoUseCase getLogo) {
    this.getLogo = getLogo;
  }

  @GetMapping("/o/{organizationId}/branding/logo")
  @ResponseBody
  public ResponseEntity<byte[]> show(
      @PathVariable final UUID organizationId,
      @RequestParam(name = "v", required = false) final String version) {
    return getLogo
        .handle(organizationId)
        .map(logo -> respond(logo, version != null && !version.isBlank()))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private static ResponseEntity<byte[]> respond(
      final OrganizationLogo logo, final boolean versioned) {
    final CacheControl cache =
        versioned
            ? CacheControl.maxAge(LONG_MAX_AGE).cachePublic().immutable()
            : CacheControl.maxAge(SHORT_MAX_AGE).cachePublic();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(logo.contentType()))
        .cacheControl(cache)
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "default-src 'none'; sandbox")
        .body(logo.content());
  }
}
