package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.bootstrapplatformclient.PlatformClientRepository;
import com.clavaris.clientregistry.domain.model.PlatformClient;
import com.clavaris.clientregistry.domain.model.PlatformScopes;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * TD-SEC-010: same dedicated coverage as {@link OrganizationRegisteredClientRepositoryTest}, for
 * the platform tier's own adapter — {@code findById} was unreachable dead code until TD-SEC-003
 * wired {@code JdbcOAuth2AuthorizationService} in front of it.
 */
class PlatformRegisteredClientRepositoryTest {

  @Test
  void findByIdReturnsNullForAMalformedId() {
    PlatformClientRepository platformClients = mock(PlatformClientRepository.class);
    PlatformRegisteredClientRepository repository =
        new PlatformRegisteredClientRepository(platformClients);

    RegisteredClient found = repository.findById("not-a-uuid");

    assertThat(found).isNull();
    verify(platformClients, never()).findById(any());
  }

  @Test
  void findByIdReturnsNullWhenNoClientExistsForThatId() {
    PlatformClientRepository platformClients = mock(PlatformClientRepository.class);
    UUID id = UUID.randomUUID();
    when(platformClients.findById(id)).thenReturn(Optional.empty());
    PlatformRegisteredClientRepository repository =
        new PlatformRegisteredClientRepository(platformClients);

    RegisteredClient found = repository.findById(id.toString());

    assertThat(found).isNull();
  }

  @Test
  void findByIdResolvesARealPlatformClientByItsOwnId() {
    PlatformClient client =
        PlatformClient.register(
            "a-platform-client", "$argon2id$hashed", PlatformScopes.BOOTSTRAP_DEFAULT);
    PlatformClientRepository platformClients = mock(PlatformClientRepository.class);
    when(platformClients.findById(client.id())).thenReturn(Optional.of(client));
    PlatformRegisteredClientRepository repository =
        new PlatformRegisteredClientRepository(platformClients);

    RegisteredClient found = repository.findById(client.id().toString());

    assertThat(found).isNotNull();
    assertThat(found.getClientId()).isEqualTo("a-platform-client");
    // ADR-0013: confidential clients only, platform tier included — same invariant
    // OrganizationRegisteredClientRepositoryTest asserts for the tenant tier.
    assertThat(found.getClientAuthenticationMethods())
        .as(
            "every PlatformClient must require client_secret_basic — never a public/PKCE-only client")
        .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
  }
}
