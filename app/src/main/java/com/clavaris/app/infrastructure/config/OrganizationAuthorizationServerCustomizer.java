package com.clavaris.app.infrastructure.config;

import com.clavaris.app.infrastructure.adapter.out.security.ClientAuthenticationFailureHandler;
import com.clavaris.app.infrastructure.adapter.out.security.ClientSecretAuthenticationSupport;
import com.clavaris.app.infrastructure.adapter.out.security.OidcDiscoveryMetadataCustomizer;
import com.clavaris.app.infrastructure.adapter.out.security.RefreshTokenRotationAuthenticationProvider;
import com.clavaris.app.infrastructure.adapter.out.security.TokenRevocationEventLogger;
import com.clavaris.app.infrastructure.adapter.out.security.WorkspaceAwareOidcUserInfoMapper;
import com.clavaris.common.application.port.CpuBoundVerificationGate;
import com.clavaris.identity.application.usecase.rotaterefreshtoken.RotateRefreshTokenUseCase;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * The {@code OAuth2AuthorizationServerConfigurer} settings of the per-Organization chain
 * (TD-ARCH-042): which repositories and services Spring Authorization Server uses, the
 * refresh-token and revocation replacements, the OIDC extras and the consent page. Split out of
 * {@link OrganizationAuthorizationServerConfig}, whose single bean method was assembling this and
 * the filter chain around it; every comment below moved with the line it explains.
 */
final class OrganizationAuthorizationServerCustomizer
    implements Customizer<OAuth2AuthorizationServerConfigurer> {

  private final RegisteredClientRepository registeredClients;
  private final AuthorizationServerSettings settings;
  private final OAuth2AuthorizationService authorizationService;
  private final OAuth2AuthorizationConsentService consentService;
  private final OAuth2TokenGenerator<?> tokenGenerator;
  private final RotateRefreshTokenUseCase rotateRefreshToken;
  private final TokenRevocationEventLogger tokenRevocationLogger;
  private final CpuBoundVerificationGate argon2BulkheadGate;

  // java:S107/PMD.ExcessiveParameterList: one parameter per collaborator SAS is configured with.
  @SuppressWarnings("java:S107")
  /* package */ OrganizationAuthorizationServerCustomizer(
      final RegisteredClientRepository registeredClients,
      final AuthorizationServerSettings settings,
      final OAuth2AuthorizationService authorizationService,
      final OAuth2AuthorizationConsentService consentService,
      final OAuth2TokenGenerator<?> tokenGenerator,
      final RotateRefreshTokenUseCase rotateRefreshToken,
      final TokenRevocationEventLogger tokenRevocationLogger,
      final CpuBoundVerificationGate argon2BulkheadGate) {
    this.registeredClients = registeredClients;
    this.settings = settings;
    this.authorizationService = authorizationService;
    this.consentService = consentService;
    this.tokenGenerator = tokenGenerator;
    this.rotateRefreshToken = rotateRefreshToken;
    this.tokenRevocationLogger = tokenRevocationLogger;
    this.argon2BulkheadGate = argon2BulkheadGate;
  }

  @Override
  public void customize(final OAuth2AuthorizationServerConfigurer server) {
    server
        .registeredClientRepository(registeredClients)
        .authorizationServerSettings(settings)
        .authorizationService(authorizationService)
        .authorizationConsentService(consentService)
        .tokenGenerator(tokenGenerator)
        // BR-ID-03: replaces SAS's own OAuth2RefreshTokenAuthenticationProvider
        // entirely for the refresh grant — same provider-swap extension point already
        // used below for ClientSecretAuthenticationProvider's password encoder. See
        // RefreshTokenRotationAuthenticationProvider's own Javadoc for why this is a
        // full replacement, not a delegating wrapper.
        .tokenEndpoint(
            tokenEndpoint ->
                tokenEndpoint.authenticationProviders(
                    providers -> {
                      providers.removeIf(
                          OAuth2RefreshTokenAuthenticationProvider.class::isInstance);
                      providers.add(
                          new RefreshTokenRotationAuthenticationProvider(
                              authorizationService, tokenGenerator, rotateRefreshToken));
                    }))
        // TD-SEC-017: every successful /oauth2/revoke call on any Organization gets a
        // structured event=token_revoked log line — see TokenRevocationEventLogger's
        // own Javadoc for why this replaces (not adds to) SAS's own default handler.
        .tokenRevocationEndpoint(
            revocation -> revocation.revocationResponseHandler(tokenRevocationLogger))
        // A genuine OIDC discovery document (/.well-known/openid-configuration), not
        // just plain OAuth2 authorization server metadata — ADR-0010 §5 requires every
        // Organization to have its own *OIDC* issuer, and this is what makes the
        // discovery filter advertise itself as one. Custom userInfoMapper (Workspace
        // feature follow-up): SAS's own default one silently drops workspace_id/
        // workspace_role from /userinfo — see WorkspaceAwareOidcUserInfoMapper's own
        // Javadoc for why, confirmed live via javap, not assumed.
        .oidc(
            oidc ->
                oidc.userInfoEndpoint(
                        userInfo -> userInfo.userInfoMapper(new WorkspaceAwareOidcUserInfoMapper()))
                    .providerConfigurationEndpoint(
                        provider ->
                            provider.providerConfigurationCustomizer(
                                OidcDiscoveryMetadataCustomizer::customize)))
        // Same fix as the platform tier's chain, same root cause — extracted to
        // ClientSecretAuthenticationSupport (code review finding: this exact block was
        // duplicated byte-for-byte across both chains) — see that class's own Javadoc.
        .clientAuthentication(
            clientAuth ->
                clientAuth
                    .errorResponseHandler(new ClientAuthenticationFailureHandler())
                    .authenticationProviders(
                        providers ->
                            ClientSecretAuthenticationSupport.useClientSecretEncoder(
                                providers, argon2BulkheadGate)))
        // TD-SEC-011 (2026-09-06): the project-owned, branded replacement for SAS's own
        // DefaultConsentPage — see ConsentController's own Javadoc for the full design
        // and OrganizationAuthorizationServerConfig.CONSENT_PATH_PATTERN's own comment above for
        // why this is one flat,
        // org-agnostic literal rather than a per-Organization path. Configuring this at
        // all switches every Organization's own consent render from SAS's previous
        // inline 200 response to a 302 redirect here — a deliberate protocol-shape
        // change, not a side effect (see AuthorizationCodeFlowIntegrationTest's own
        // updated consent tests, which now assert the redirect explicitly).
        .authorizationEndpoint(
            authorizationEndpoint ->
                authorizationEndpoint.consentPage(
                    OrganizationAuthorizationServerConfig.CONSENT_PATH_PATTERN));
  }
}
