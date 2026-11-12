package com.clavaris.app.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/** TD-ARCH-051: one definition of the grants whose token has an Account as its principal. */
class AccountPrincipalGrantsTest {

  @Test
  void theThreeGrantsThatCarryAnAccountAreRecognised() {
    assertThat(
            AccountPrincipalGrants.hasAccountPrincipal(AuthorizationGrantType.AUTHORIZATION_CODE))
        .isTrue();
    assertThat(AccountPrincipalGrants.hasAccountPrincipal(AuthorizationGrantType.REFRESH_TOKEN))
        .isTrue();
    assertThat(AccountPrincipalGrants.hasAccountPrincipal(AccountPrincipalGrants.TOKEN_EXCHANGE))
        .isTrue();
  }

  @Test
  void aClientActingForItselfAndAnUnknownGrantAreNot() {
    assertThat(
            AccountPrincipalGrants.hasAccountPrincipal(AuthorizationGrantType.CLIENT_CREDENTIALS))
        .isFalse();
    assertThat(AccountPrincipalGrants.hasAccountPrincipal(new AuthorizationGrantType("urn:other")))
        .isFalse();
    assertThat(AccountPrincipalGrants.hasAccountPrincipal(null)).isFalse();
  }

  @Test
  void theTokenExchangeConstantIsRfc8693sOwnName() {
    assertThat(AccountPrincipalGrants.TOKEN_EXCHANGE.getValue())
        .isEqualTo("urn:ietf:params:oauth:grant-type:token-exchange");
  }
}
