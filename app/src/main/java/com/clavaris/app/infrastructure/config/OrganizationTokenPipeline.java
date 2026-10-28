package com.clavaris.app.infrastructure.config;

import com.clavaris.app.infrastructure.adapter.out.persistence.HashedTokenOAuth2AuthorizationService;
import com.clavaris.app.infrastructure.adapter.out.security.AuthenticationContextClaimsCustomizer;
import com.clavaris.app.infrastructure.adapter.out.security.BearerTokenHasher;
import com.clavaris.app.infrastructure.adapter.out.security.EmailClaimsCustomizer;
import com.clavaris.app.infrastructure.adapter.out.security.OidcSessionIdRecorder;
import com.clavaris.app.infrastructure.adapter.out.security.OrganizationJwksPublishingSource;
import com.clavaris.app.infrastructure.adapter.out.security.OrganizationJwtIssuerValidator;
import com.clavaris.app.infrastructure.adapter.out.security.OrganizationScopedJwkSource;
import com.clavaris.app.infrastructure.adapter.out.security.PhoneClaimsCustomizer;
import com.clavaris.app.infrastructure.adapter.out.security.ProfilePictureClaimsCustomizer;
import com.clavaris.app.infrastructure.adapter.out.security.SessionBackedRefreshTokenGenerator;
import com.clavaris.app.infrastructure.adapter.out.security.WorkspaceRoleClaimsCustomizer;
import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.application.usecase.issuerefreshtoken.IssueRefreshTokenUseCase;
import com.clavaris.identity.application.usecase.recordsessionoidcid.RecordSessionOidcIdUseCase;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.infrastructure.adapter.out.security.OrganizationSigningKeyMaterialFactory;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

/**
 * The pre-DSL object construction of the per-Organization chain (TD-ARCH-042, originally TD-NEW-1):
 * each factory is a self-contained sub-assembly with a narrow input/output. Split out of {@link
 * OrganizationAuthorizationServerConfig} so that class keeps only the chain itself.
 */
// PMD.ExcessiveImports/CouplingBetweenObjects: one import per distinct claims customizer or SAS
// type assembled here, the same "wiring, not sprawl" reasoning the config class documents.
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects"})
final class OrganizationTokenPipeline {

  private OrganizationTokenPipeline() {}

  /**
   * The dynamic, per-request-resolved JWKS pair (TD-SEC-008) plus the {@code /userinfo} decoder
   * built on top of the publishing one — see the call site's own Javadoc for why signing and
   * publishing deliberately stay two different {@link JWKSource} instances.
   */
  /* package */ static JwtDecoderAndJwkSources buildJwksAndUserInfoDecoder(
      final SigningKeyRepository signingKeys,
      final OrganizationSigningKeyMaterialFactory keyMaterial,
      final Duration jwksOverlapDuration) {
    final JWKSource<SecurityContext> signingJwkSource =
        new OrganizationScopedJwkSource(keyMaterial);
    final JWKSource<SecurityContext> jwksPublishingSource =
        new OrganizationJwksPublishingSource(signingKeys, keyMaterial, jwksOverlapDuration);
    final NimbusJwtDecoder userInfoJwtDecoder =
        NimbusJwtDecoder.withJwkSource(jwksPublishingSource).build();
    userInfoJwtDecoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), new OrganizationJwtIssuerValidator()));
    return new JwtDecoderAndJwkSources(signingJwkSource, jwksPublishingSource, userInfoJwtDecoder);
  }

  /** {@code signingJwkSource}, {@code jwksPublishingSource}, {@code userInfoJwtDecoder} bundled. */
  /* package */ record JwtDecoderAndJwkSources(
      JWKSource<SecurityContext> signingJwkSource,
      JWKSource<SecurityContext> jwksPublishingSource,
      NimbusJwtDecoder userInfoJwtDecoder) {}

  /**
   * ADR-0016/Workspace-feature/TD-SEC-016 token-issuance pipeline: one {@link JwtEncoder}, every
   * ID-/access-token claims customizer composed into {@code JwtGenerator}'s single customizer slot
   * (see the call site's own Javadoc for why they can't be set as separate calls), and BR-ID-03's
   * refresh-token fallback wired via {@link DelegatingOAuth2TokenGenerator}.
   */
  /* package */ static OAuth2TokenGenerator<?> buildTokenGenerator(
      final JWKSource<SecurityContext> signingJwkSource,
      final OAuth2TokenCustomizer<JwtEncodingContext> tokenIssuanceLogger,
      final WorkspaceRoleClaimsCustomizer workspaceRoleClaims,
      final IssueRefreshTokenUseCase issueRefreshToken,
      final RecordSessionOidcIdUseCase recordSessionOidcId,
      final AccountRepository accounts,
      final String clavarisBaseUrl) {
    final JwtEncoder jwtEncoder = new NimbusJwtEncoder(signingJwkSource);
    final JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
    final AuthenticationContextClaimsCustomizer authenticationContextClaims =
        new AuthenticationContextClaimsCustomizer();
    final ProfilePictureClaimsCustomizer profilePictureClaims =
        new ProfilePictureClaimsCustomizer(accounts, clavarisBaseUrl);
    final EmailClaimsCustomizer emailClaims = new EmailClaimsCustomizer(accounts);
    final PhoneClaimsCustomizer phoneClaims = new PhoneClaimsCustomizer(accounts);
    final OidcSessionIdRecorder sessionIdRecorder = new OidcSessionIdRecorder(recordSessionOidcId);
    jwtGenerator.setJwtCustomizer(
        context -> {
          tokenIssuanceLogger.customize(context);
          authenticationContextClaims.customize(context);
          workspaceRoleClaims.customize(context);
          profilePictureClaims.customize(context);
          emailClaims.customize(context);
          phoneClaims.customize(context);
          sessionIdRecorder.customize(context);
        });
    return new DelegatingOAuth2TokenGenerator(
        jwtGenerator, new SessionBackedRefreshTokenGenerator(issueRefreshToken));
  }

  /**
   * TD-SEC-003/TD-SEC-019: the JDBC-backed, HMAC-hashed {@link OAuth2AuthorizationService} shared
   * by both this tier's {@code /authorize} and {@code /token} — see the call site's own Javadoc for
   * why this tier (unlike the platform one) has BR-ID-03 refresh-token survivability at stake.
   */
  /* package */ static OAuth2AuthorizationService buildAuthorizationService(
      final JdbcTemplate jdbcTemplate,
      final RegisteredClientRepository registeredClients,
      final BearerTokenHasher bearerTokenHasher) {
    return new HashedTokenOAuth2AuthorizationService(
        new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClients), bearerTokenHasher);
  }
}
