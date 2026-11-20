package com.clavaris.clientregistry.application.usecase.updateoauthclientaccesstokenttl;

/** TD-SEC-104 / ADR-0049: the access-token lifetime of one client. */
@FunctionalInterface
public interface UpdateOAuthClientAccessTokenTtlUseCase {

  void handle(UpdateOAuthClientAccessTokenTtlCommand command);
}
