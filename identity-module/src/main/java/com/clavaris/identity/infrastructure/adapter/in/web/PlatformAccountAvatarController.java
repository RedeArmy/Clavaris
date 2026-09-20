package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.getplatformaccountavatar.GetPlatformAccountAvatarUseCase;
import com.clavaris.identity.application.usecase.getplatformaccountavatar.PlatformAccountAvatarResult;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0026: {@code GET /platform/avatars/{platformAccountId}} — {@link AccountAvatarController}'s
 * platform-tier sibling, same unauthenticated/cacheable shape, no Organization to scope by.
 *
 * <p>{@code @Controller} + {@code @ResponseBody}, not {@code @RestController} — same OIDC/
 * hosted-UI-surface rationale {@link AccountAvatarController}'s own Javadoc documents, including
 * its own identical java:S6833 suppression.
 */
@SuppressWarnings("java:S6833")
@Controller
public class PlatformAccountAvatarController {

  private static final Duration CACHE_MAX_AGE = Duration.ofMinutes(15);

  private final GetPlatformAccountAvatarUseCase getAvatar;

  public PlatformAccountAvatarController(final GetPlatformAccountAvatarUseCase getAvatar) {
    this.getAvatar = getAvatar;
  }

  @SuppressWarnings("PMD.OnlyOneReturn") // two real, distinct exits — same rationale
  // AccountAvatarController's own identical suppression documents.
  @GetMapping("/platform/avatars/{platformAccountId}")
  @ResponseBody
  public ResponseEntity<byte[]> show(@PathVariable final UUID platformAccountId) {
    final PlatformAccountAvatarResult result =
        getAvatar
            .handle(new PlatformAccountId(platformAccountId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    if (result instanceof PlatformAccountAvatarResult.Redirect(String externalUrl)) {
      return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(externalUrl)).build();
    }

    final PlatformAccountAvatarResult.Content content =
        (PlatformAccountAvatarResult.Content) result;
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(content.contentType()))
        .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePublic())
        .body(content.content());
  }
}
