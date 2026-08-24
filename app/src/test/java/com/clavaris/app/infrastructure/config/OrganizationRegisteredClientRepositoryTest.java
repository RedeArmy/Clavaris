package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

/**
 * TD-SEC-010: {@code findById} was unreachable dead code until TD-SEC-003 wired {@code
 * JdbcOAuth2AuthorizationService} in front of it — this is the first dedicated coverage of it, not
 * just the incidental exercise the real Authorization Code flow integration tests give the happy
 * path. Sets {@link AuthorizationServerContextHolder} directly rather than going through a real
 * HTTP request, the same thread-local SAS's own {@code AuthorizationServerContextFilter} populates
 * per request (spike 0001's Appendix C addendum, {@link CurrentOrganizationContext}'s own Javadoc).
 */
class OrganizationRegisteredClientRepositoryTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  @AfterEach
  void resetContext() {
    AuthorizationServerContextHolder.resetContext();
  }

  @Test
  void findByIdReturnsNullForAMalformedIdEvenWithARealOrganizationContext() {
    // The previously-uncovered branch: a real Organization context is set (so the method gets
    // past its first guard clause), but the id SAS hands back isn't a parseable UUID at all.
    setOrganizationContext(ORGANIZATION_ID);
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    OrganizationRegisteredClientRepository repository =
        new OrganizationRegisteredClientRepository(oauthClients);

    RegisteredClient found = repository.findById("not-a-uuid");

    assertThat(found).isNull();
    verify(oauthClients, never()).findById(any());
  }

  @Test
  void findByIdReturnsNullWhenTheCurrentIssuerIsNotAnOrganizationIssuer() {
    // The realistic "no org" case — outside of a real SAS-managed request (this chain never
    // calls findById outside one), the thread-local context holder has nothing set for the
    // current thread, so the actual guard this exercises is the "issuer doesn't contain /o/"
    // branch in CurrentOrganizationContext, not an entirely absent context.
    AuthorizationServerContextHolder.setContext(
        issuerContext("https://clavaris.example.com/oauth2/token"));
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    OrganizationRegisteredClientRepository repository =
        new OrganizationRegisteredClientRepository(oauthClients);

    RegisteredClient found = repository.findById(UUID.randomUUID().toString());

    assertThat(found).isNull();
  }

  @Test
  void findByIdResolvesARealClientBelongingToTheCurrentOrganization() {
    setOrganizationContext(ORGANIZATION_ID);
    OAuthClient client =
        OAuthClient.register(
            ORGANIZATION_ID,
            "a-client-id",
            "argon2id$hashed",
            List.of("https://example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"));
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    OrganizationRegisteredClientRepository repository =
        new OrganizationRegisteredClientRepository(oauthClients);

    RegisteredClient found = repository.findById(client.id().toString());

    assertThat(found).isNotNull();
    assertThat(found.getClientId()).isEqualTo("a-client-id");
    // ADR-0013: confidential clients only — no code path may ever hand back
    // ClientAuthenticationMethod.NONE (a public/PKCE-only client). Asserted directly here rather
    // than only implied by OAuthClient always carrying a non-blank clientSecretHash, since this is
    // the one place that value actually turns into the authentication method SAS enforces at the
    // protocol level — a regression here is exactly the kind of silent widening ADR-0013 exists to
    // prevent.
    assertThat(found.getClientAuthenticationMethods())
        .as("every OAuthClient must require client_secret_basic — never a public/PKCE-only client")
        .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
  }

  @Test
  void findByIdReturnsNullForARealClientBelongingToADifferentOrganization() {
    // ADR-0010 §5: "structurally impossible, not policy-disallowed" — same isolation bar
    // findByClientId already enforces.
    setOrganizationContext(ORGANIZATION_ID);
    UUID otherOrganizationId = UUID.randomUUID();
    OAuthClient client =
        OAuthClient.register(
            otherOrganizationId,
            "someone-elses-client",
            "argon2id$hashed",
            List.of("https://example.com/callback"),
            List.of("authorization_code"),
            List.of("openid"));
    OAuthClientRepository oauthClients = mock(OAuthClientRepository.class);
    when(oauthClients.findById(client.id())).thenReturn(Optional.of(client));
    OrganizationRegisteredClientRepository repository =
        new OrganizationRegisteredClientRepository(oauthClients);

    RegisteredClient found = repository.findById(client.id().toString());

    assertThat(found).isNull();
  }

  private static void setOrganizationContext(final UUID organizationId) {
    AuthorizationServerContextHolder.setContext(
        issuerContext("https://clavaris.example.com/o/" + organizationId));
  }

  private static AuthorizationServerContext issuerContext(final String issuer) {
    return new AuthorizationServerContext() {
      @Override
      public String getIssuer() {
        return issuer;
      }

      @Override
      public AuthorizationServerSettings getAuthorizationServerSettings() {
        // Not read by the production code this test exercises — only the issuer is; a real
        // settings object isn't needed for this test to be meaningful.
        return null;
      }
    };
  }
}
