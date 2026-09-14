package com.clavaris.clientregistry.application.usecase.listoauthclientspaged;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.common.domain.model.KeysetPage;

public class ListOAuthClientsPagedService implements ListOAuthClientsPagedUseCase {

  private final OAuthClientRepository oauthClients;

  public ListOAuthClientsPagedService(final OAuthClientRepository oauthClients) {
    this.oauthClients = oauthClients;
  }

  @Override
  public KeysetPage<OAuthClient> handle(final ListOAuthClientsPagedQuery query) {
    return oauthClients.findKeysetPageByOrganizationId(query.organizationId(), query.pageRequest());
  }
}
