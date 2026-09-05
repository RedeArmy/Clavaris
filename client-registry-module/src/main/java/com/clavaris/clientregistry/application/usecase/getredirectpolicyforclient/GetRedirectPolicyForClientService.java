package com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient;

import com.clavaris.clientregistry.application.usecase.setredirectpolicyforclient.RedirectPolicyRepository;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.UUID;

/**
 * Read side of the redirect-policy surface. Depends on {@code RedirectPolicyRepository} directly
 * (the same port {@code SetRedirectPolicyForClientService} writes through) rather than duplicating
 * a second repository interface for the same table — same "shared port, separate use-case folders"
 * precedent {@code GetAccountAuthenticationPolicyForOrganizationService} already establishes.
 */
public class GetRedirectPolicyForClientService implements GetRedirectPolicyForClientUseCase {

  private final RedirectPolicyRepository policies;

  public GetRedirectPolicyForClientService(final RedirectPolicyRepository policies) {
    this.policies = policies;
  }

  @Override
  public RedirectPolicy handle(final UUID oauthClientId) {
    return policies
        .findByOAuthClientId(oauthClientId)
        .orElseGet(() -> RedirectPolicy.unconfigured(oauthClientId));
  }
}
