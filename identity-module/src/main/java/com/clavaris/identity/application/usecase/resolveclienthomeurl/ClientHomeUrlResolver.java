package com.clavaris.identity.application.usecase.resolveclienthomeurl;

import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;

/**
 * Where a person can be sent back to the consuming application from a hosted page that ends in a
 * "check your email" message (sign-up, forgot password): the application's own home page, so the
 * page is never a dead end.
 *
 * <p>The answer is only ever derived from what the client registered (the origin of one of its own
 * {@code redirect_uri}s), never from a value the request carries, so it cannot be turned into an
 * open redirect. Deliberately does not reference client-registry-module's {@code OAuthClient}
 * directly — same module-independence rule {@code RedirectUrlResolver} follows; implemented in
 * {@code app}.
 */
@FunctionalInterface
public interface ClientHomeUrlResolver {

  /**
   * @param organizationId the path's own Organization — a {@code clientId} that belongs to a
   *     different Organization is treated exactly like an unknown one (empty), the same BR-ORG-02
   *     cross-tenant defence in depth every other client lookup applies.
   * @param clientId the OAuth2 client id, or {@code null} when the request carries no client
   *     context at all.
   * @return the application's home URL, or empty when there is nothing safe to link to.
   */
  Optional<String> resolve(OrganizationId organizationId, String clientId);
}
