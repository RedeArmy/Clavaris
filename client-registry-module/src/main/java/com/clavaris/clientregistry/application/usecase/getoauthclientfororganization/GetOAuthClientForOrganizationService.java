package com.clavaris.clientregistry.application.usecase.getoauthclientfororganization;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.Optional;

public class GetOAuthClientForOrganizationService implements GetOAuthClientForOrganizationUseCase {

  private final OAuthClientRepository oauthClients;

  public GetOAuthClientForOrganizationService(final OAuthClientRepository oauthClients) {
    this.oauthClients = oauthClients;
  }

  @Override
  public Optional<OAuthClient> handle(final GetOAuthClientForOrganizationQuery query) {
    return oauthClients
        .findByClientId(query.clientId())
        .filter(found -> found.organizationId().equals(query.organizationId()));
  }
}
