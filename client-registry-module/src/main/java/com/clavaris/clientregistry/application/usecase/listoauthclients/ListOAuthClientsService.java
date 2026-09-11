package com.clavaris.clientregistry.application.usecase.listoauthclients;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.UUID;

/**
 * Read-only — same "an empty list is a valid, safe answer either way" posture {@code
 * ListOrganizationClientsService} already establishes for the sibling Secret Key concept.
 */
public class ListOAuthClientsService implements ListOAuthClientsUseCase {

  private final OAuthClientRepository oauthClients;

  public ListOAuthClientsService(final OAuthClientRepository oauthClients) {
    this.oauthClients = oauthClients;
  }

  @Override
  public List<OAuthClient> handle(final UUID organizationId) {
    return oauthClients.findAllByOrganizationId(organizationId);
  }
}
