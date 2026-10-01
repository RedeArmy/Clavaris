package com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount;

import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import java.util.List;

public class ListWebAuthnCredentialsForAccountService
    implements ListWebAuthnCredentialsForAccountUseCase {

  private final WebAuthnCredentialRepository credentials;

  public ListWebAuthnCredentialsForAccountService(final WebAuthnCredentialRepository credentials) {
    this.credentials = credentials;
  }

  @Override
  public List<WebAuthnCredential> handle(final ListWebAuthnCredentialsForAccountQuery query) {
    return credentials.findAllByAccountId(query.accountId());
  }
}
