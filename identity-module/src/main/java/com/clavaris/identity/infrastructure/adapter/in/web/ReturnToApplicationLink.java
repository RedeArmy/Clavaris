package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.resolveclienthomeurl.ClientHomeUrlResolver;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Puts the "back to the application" link on the hosted pages that end in a "check your email"
 * message, so they are never a dead end for someone who arrived from a consuming application.
 *
 * <p>The page only gets a link when the request names a client of this Organization; a direct visit
 * with no client context renders without one, exactly as before.
 */
@Component
public class ReturnToApplicationLink {

  /** The model attribute the pages read; absent when there is nothing to link to. */
  public static final String HOME_URL = "homeUrl";

  private final ClientHomeUrlResolver homeUrlResolver;

  public ReturnToApplicationLink(final ClientHomeUrlResolver homeUrlResolver) {
    this.homeUrlResolver = homeUrlResolver;
  }

  /**
   * Adds {@link #HOME_URL} to {@code model} when the client has a home page to return to, and keeps
   * {@code clientId} on the model so the page's own links (sign in) can carry it on.
   */
  public void addTo(final Model model, final UUID organizationId, final String clientId) {
    model.addAttribute("clientId", clientId);
    homeUrlResolver
        .resolve(new OrganizationId(organizationId), clientId)
        .ifPresent(url -> model.addAttribute(HOME_URL, url));
  }
}
