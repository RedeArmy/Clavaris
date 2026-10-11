package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.SocialProvider;
import java.util.Locale;
import java.util.UUID;
import org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder;

/**
 * The address of a provider's own Connections page, worked out from {@link
 * PlatformSocialConnectionsController}'s mapping rather than typed out a second time, so a redirect
 * back to the page cannot drift from the page it returns to.
 */
final class SocialConnectionPaths {

  private SocialConnectionPaths() {
    // Static helper only.
  }

  /** The path (no host) of {@code provider}'s page under {@code organizationId}'s Connections. */
  /* package */ static String pageOf(final UUID organizationId, final SocialProvider provider) {
    // The controller's own "show" method names the mapping; the arguments that are not part of the
    // path (the request and the model) are not needed to build it.
    return MvcUriComponentsBuilder.fromMethodName(
            PlatformSocialConnectionsController.class,
            "show",
            null,
            organizationId,
            provider.name().toLowerCase(Locale.ROOT),
            null)
        .build()
        .getPath();
  }
}
