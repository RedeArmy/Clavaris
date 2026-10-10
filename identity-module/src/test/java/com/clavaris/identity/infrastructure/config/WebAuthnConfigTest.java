package com.clavaris.identity.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.clavaris.identity.application.usecase.registerwebauthncredential.RelyingPartyFactory;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RelyingParty;
import org.junit.jupiter.api.Test;

/**
 * The relying party a passkey is created under: its name is what a browser shows when it asks to
 * save the passkey, so it is the Organization's; its id and origins stay the deployment's own.
 */
class WebAuthnConfigTest {

  private static final String BASE_URL = "https://id.example.test";

  private final WebAuthnConfig config = new WebAuthnConfig();
  private final CredentialRepository credentials = mock(CredentialRepository.class);
  private final RelyingPartyFactory factory = config.relyingPartyFactory(BASE_URL, credentials);

  @Test
  void theRelyingPartyIsShownUnderTheGivenName() {
    final RelyingParty relyingParty = factory.named("Acme Analytics");

    assertThat(relyingParty.getIdentity().getName()).isEqualTo("Acme Analytics");
  }

  @Test
  void theIdAndOriginsAreTheDeploymentsOwnWhateverTheName() {
    final RelyingParty relyingParty = factory.named("Acme Analytics");

    assertThat(relyingParty.getIdentity().getId()).isEqualTo("id.example.test");
    assertThat(relyingParty.getOrigins()).containsExactly(BASE_URL);
  }

  // With no name the browser is told the host, which says nothing about Clavaris.
  @Test
  void withNoNameTheRelyingPartyIsShownUnderItsHostNotClavaris() {
    assertThat(factory.named(null).getIdentity().getName()).isEqualTo("id.example.test");
    assertThat(factory.named("   ").getIdentity().getName()).isEqualTo("id.example.test");
  }

  @Test
  void theNameIsTrimmed() {
    assertThat(factory.named("  Acme Analytics ").getIdentity().getName())
        .isEqualTo("Acme Analytics");
  }
}
