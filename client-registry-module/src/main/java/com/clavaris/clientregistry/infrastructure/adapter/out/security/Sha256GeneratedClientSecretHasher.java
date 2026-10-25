package com.clavaris.clientregistry.infrastructure.adapter.out.security;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.GeneratedClientSecretHasher;
import com.clavaris.clientregistry.domain.service.HighEntropyClientSecret;
import org.springframework.stereotype.Component;

/** Implements {@link GeneratedClientSecretHasher} with {@link HighEntropyClientSecret}. */
// PMD.AtLeastOneConstructor: holds no state, only the one delegation below — same rationale as
// WorkspaceAwareOidcUserInfoMapper's own identical suppression.
@SuppressWarnings("PMD.AtLeastOneConstructor")
@Component
class Sha256GeneratedClientSecretHasher implements GeneratedClientSecretHasher {

  @Override
  public String hash(final String rawSecret) {
    return HighEntropyClientSecret.hash(rawSecret);
  }
}
