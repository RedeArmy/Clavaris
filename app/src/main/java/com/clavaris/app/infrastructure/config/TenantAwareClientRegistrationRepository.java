package com.clavaris.app.infrastructure.config;

import com.clavaris.identity.infrastructure.adapter.in.web.SocialLoginRedirectController;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialCipher;
import com.clavaris.organization.application.usecase.setorganizationsocialcredential.OrganizationSocialCredentialRepository;
import com.clavaris.organization.domain.model.OrganizationSocialCredential;
import com.clavaris.organization.domain.model.SocialProvider;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * ADR-0022 (amending ADR-0020 Decision 4): {@code oauth2Login()} ({@code SocialLoginConfig})
 * resolves a {@link ClientRegistration} through whichever {@link ClientRegistrationRepository} bean
 * exists in the context — this class replaces Spring Boot's implicit autoconfigured one (defining
 * any bean of this type suppresses {@code OAuth2ClientConfigurations.ClientRegistrationRepository
 * Configuration}'s own {@code @ConditionalOnMissingBean} bean), so it must reconstruct the exact
 * same shared registrations that bean would have produced. Reuses Boot's own real merge logic for
 * that — {@link OAuth2ClientPropertiesMapper#asClientRegistrations()} — rather than hand-rebuilding
 * registrations via {@code CommonOAuth2Provider} directly: a first version of this class did
 * exactly that and would have silently ignored {@code
 * spring.security.oauth2.client.provider.github.*} overrides (confirmed live in {@code
 * SocialLoginIntegrationTest}, which points GitHub's endpoints at a local stub via
 * {@code @DynamicPropertySource} — a real correctness bug caught before it shipped, not discovered
 * via a failing test).
 *
 * <p>{@link OAuth2ClientProperties} itself is bound by hand ({@link Binder}, {@code
 * "spring.security.oauth2.client"}), not autowired — confirmed live (a second real correctness bug
 * caught by the same test) that in Spring Boot 4.1's split-module autoconfiguration, defining this
 * bean suppresses the *entire* {@code OAuth2ClientAutoConfiguration} class, not just its inner
 * {@code ClientRegistrationRepositoryConfiguration}, so the {@code OAuth2ClientProperties} bean
 * itself is gone too — {@code Binder} reads the same property prefix independently of any
 * conditional autoconfiguration.
 *
 * <p>Layers a PRODUCTION Organization's own credentials on top of the shared registration when one
 * has opted in. Deliberately does not change the registration-id scheme — {@code "google"}/{@code
 * "github"} stay literal. Both the outbound {@code /oauth2/authorization/{id}} redirect ({@code
 * SocialLoginRedirectController}) and the inbound {@code /login/oauth2/code/{id}} callback ({@code
 * SocialLoginAuthenticationSuccessHandler}) resolve through this same repository within the same
 * browser session, so reading the current Organization back out of {@link
 * SocialLoginRedirectController#ORGANIZATION_ID_SESSION_ATTRIBUTE} (the exact same {@code
 * HttpSession} attribute that controller already sets) is naturally consistent across the round
 * trip with no need to embed the Organization id in the registration id itself. Platform-tier login
 * ({@code /platform/login/social/{provider}}) already clears this attribute before redirecting —
 * falls through to the shared registration automatically, correctly out of this feature's scope
 * (ADR-0022 is Organization/tenant-only).
 *
 * <p>{@code RequestContextHolder}: the standard Spring idiom for a singleton bean needing current-
 * request state without becoming request-scoped itself — {@code oauth2Login()}'s own filters always
 * run within a real HTTP request, so this is never called outside one.
 *
 * <p>{@code PMD.OnlyOneReturn}: several methods here have genuinely distinct early-exit branches
 * (null/unknown registration id, no request context, no session, no session attribute, malformed
 * attribute) — same rationale {@code SocialLoginRedirectController}'s own identical suppression
 * already documents for the same class of guard-clause-heavy resolution logic.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn"})
@Component
class TenantAwareClientRegistrationRepository implements ClientRegistrationRepository {

  private final Map<String, ClientRegistration> sharedRegistrations;
  private final OrganizationSocialCredentialRepository credentials;
  private final OrganizationSocialCredentialCipher cipher;

  /* package */ TenantAwareClientRegistrationRepository(
      final Environment environment,
      final OrganizationSocialCredentialRepository credentials,
      final OrganizationSocialCredentialCipher cipher) {
    final OAuth2ClientProperties clientProperties =
        Binder.get(environment)
            .bind("spring.security.oauth2.client", OAuth2ClientProperties.class)
            .orElseGet(OAuth2ClientProperties::new);
    this.sharedRegistrations =
        new OAuth2ClientPropertiesMapper(clientProperties).asClientRegistrations();
    this.credentials = credentials;
    this.cipher = cipher;
  }

  @Override
  public ClientRegistration findByRegistrationId(final String registrationId) {
    final ClientRegistration shared = sharedRegistrations.get(registrationId);
    if (shared == null) {
      return null;
    }
    final SocialProvider provider = toOrganizationModuleProvider(registrationId);
    if (provider == null) {
      return shared;
    }
    return currentOrganizationId()
        .flatMap(
            organizationId -> credentials.findByOrganizationIdAndProvider(organizationId, provider))
        .map(credential -> withTenantOwnCredential(shared, credential))
        .orElse(shared);
  }

  private static SocialProvider toOrganizationModuleProvider(final String registrationId) {
    try {
      return SocialProvider.valueOf(registrationId.toUpperCase(Locale.ROOT));
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }

  private ClientRegistration withTenantOwnCredential(
      final ClientRegistration shared, final OrganizationSocialCredential credential) {
    return ClientRegistration.withClientRegistration(shared)
        .clientId(credential.clientId())
        .clientSecret(cipher.decrypt(credential.clientSecretEncrypted()))
        .build();
  }

  private Optional<UUID> currentOrganizationId() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
      return Optional.empty();
    }
    final HttpSession session = attrs.getRequest().getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    final Object raw =
        session.getAttribute(SocialLoginRedirectController.ORGANIZATION_ID_SESSION_ATTRIBUTE);
    if (raw == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(raw.toString()));
    } catch (final IllegalArgumentException _) {
      // Malformed session state should never happen (SocialLoginRedirectController always writes
      // a real UUID's own toString()) — fail safe to the shared registration rather than throw,
      // same "malformed/absent input is skipped, not fatal" convention this codebase's rate-limit
      // identifiers already follow.
      return Optional.empty();
    }
  }
}
