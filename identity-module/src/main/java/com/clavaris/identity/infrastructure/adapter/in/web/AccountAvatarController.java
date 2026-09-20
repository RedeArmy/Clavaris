package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.getaccountavatar.AccountAvatarResult;
import com.clavaris.identity.application.usecase.getaccountavatar.GetAccountAvatarQuery;
import com.clavaris.identity.application.usecase.getaccountavatar.GetAccountAvatarUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0026: {@code GET /o/{organizationId}/avatars/{accountId}} — the one stable URL every OIDC
 * {@code picture} claim this codebase issues points at, and what {@code account-profile.html}'s own
 * self-service page and every dashboard list showing an Account both render directly. Deliberately
 * unauthenticated (served by {@code DefaultSecurityConfig}'s own {@code permitAll()} catch-all
 * chain, no new security config needed) — a browser's own {@code <img src>} request, or a fetch
 * from a completely different origin (the consuming application's own UI), can never carry this
 * server's session cookie or a bearer token.
 *
 * <p>{@link AccountAvatarResult.Redirect} (a social provider's own external picture URL) becomes a
 * real {@code 302}, not a proxied fetch — the browser talks to that provider's own CDN directly,
 * Clavaris never touches those bytes. {@link AccountAvatarResult.Content} (a real Supabase-stored
 * upload, or {@code GetAccountAvatarService}'s own generated-initials default) is streamed back
 * with a real {@code Cache-Control}, since this endpoint's own content genuinely doesn't change on
 * every request the way a login page's own HTML does.
 */
@RestController
public class AccountAvatarController {

  // A real cache lifetime, not zero/no-store like every other hosted page on this codebase's own
  // catch-all chain — an avatar changes rarely, and this same URL is exactly what an OIDC picture
  // claim keeps pointing at across many separate token issuances, so a short-lived browser/CDN
  // cache is a real, safe win, not a staleness risk worth avoiding.
  private static final Duration CACHE_MAX_AGE = Duration.ofMinutes(15);

  private final GetAccountAvatarUseCase getAvatar;

  public AccountAvatarController(final GetAccountAvatarUseCase getAvatar) {
    this.getAvatar = getAvatar;
  }

  // PMD.OnlyOneReturn: two real, distinct exits — a redirect to an external provider URL, or a
  // real content response — same rationale AccountAvatarResult's own sealed-interface shape
  // documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping("/o/{organizationId}/avatars/{accountId}")
  public ResponseEntity<byte[]> show(
      @PathVariable final UUID organizationId, @PathVariable final UUID accountId) {
    final AccountAvatarResult result =
        getAvatar
            .handle(
                new GetAccountAvatarQuery(
                    new OrganizationId(organizationId), new AccountId(accountId)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    if (result instanceof AccountAvatarResult.Redirect(String externalUrl)) {
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(externalUrl)).build();
    }

    final AccountAvatarResult.Content content = (AccountAvatarResult.Content) result;
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePublic())
        .body(content.content());
  }
}
