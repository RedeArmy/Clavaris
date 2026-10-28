package com.clavaris.app.infrastructure.config;

import com.clavaris.app.infrastructure.adapter.in.web.filter.AntiAbuseRateLimitingFilter;
import com.clavaris.app.infrastructure.adapter.in.web.filter.IdempotencyKeyFilter;
import com.clavaris.app.infrastructure.adapter.in.web.filter.IdempotencyKeyStore;
import com.clavaris.app.infrastructure.adapter.in.web.filter.OrganizationClientOwnershipFilter;
import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimitIdentifiers;
import com.clavaris.app.infrastructure.adapter.in.web.filter.RateLimiter;
import com.clavaris.app.infrastructure.adapter.out.security.IdempotencyKeyHasher;
import com.clavaris.app.infrastructure.adapter.out.security.RateLimitKeyHasher;
import com.clavaris.common.application.port.SecurityMetricsRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.infrastructure.adapter.out.security.PlatformSigningKeyMaterial;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

/**
 * BR-PLATFORM-02: the entire {@code /api/v1/admin/*} surface accepts platform-tier tokens only — no
 * tenant's own {@code OAuthClient} is ever accepted here, not even for actions on its own
 * Organization. Validates the incoming Bearer token's signature directly against {@link
 * PlatformSigningKeyMaterial}'s own public key — no HTTP round-trip to this same process's own
 * {@code /oauth2/jwks} endpoint, since the key is already in memory right here.
 *
 * <p>TD-SEC-030 (SDE-III review, 2026-08-26): before this, zero rate limiting existed anywhere on
 * this surface — confirmed by grep, this was the only security chain in the whole app with no
 * {@code AntiAbuseRateLimitingFilter} at all. A compromised or over-scoped {@code PlatformClient}
 * token could loop indefinitely against, e.g., {@code POST /organizations/*:delete} (the single
 * most destructive call this system exposes) with zero friction and no anomaly signal. Keyed by the
 * authenticated {@code client_id} ({@link RateLimitIdentifiers#authenticatedPlatformClientId}), not
 * source IP: every caller here is a backend service (an operator's tooling, or a consuming
 * application's own backend), not a browser — IP is not a meaningful identity axis for
 * machine-to-machine traffic, same reasoning {@code PlatformAuthorizationServerConfig}'s own {@code
 * platform-token:client} rule already established for {@code client_credentials} calls. Two layers,
 * same ADR-0010 §6.1 "defence in depth per rule" shape already used for this file's own scopes: a
 * generous blanket ceiling across the whole surface, plus a much tighter ceiling specifically on
 * the two {@code :delete} endpoints — the operations where a runaway loop does irreversible damage
 * fastest.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, used on several descriptively-named
// fields/parameters — same rationale as identity-module's own IdentityUseCaseConfig class-level
// suppression for this exact PMD-annotation-string-as-literal false positive.
// ExcessiveParameterList: ADR-0023 added two more collaborators (accounts/workspaces) to the one
// SecurityFilterChain bean this class wires — same "wiring, not sprawl" reasoning
// PlatformAuthorizationServerConfig's own identical suppression already documents.
@SuppressWarnings("PMD.ExcessiveParameterList")
@Configuration
class AdminApiSecurityConfig {

  private static final String ADMIN_API_PATH_PATTERN = "/api/v1/admin/**";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AdminApiSecurityConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  @Bean
  /* package */ JwtDecoder platformJwtDecoder(final PlatformSigningKeyMaterial platformKey) {
    return NimbusJwtDecoder.withPublicKey((RSAPublicKey) platformKey.keyPair().getPublic()).build();
  }

  // Optional Idempotency-Key support (see IdempotencyKeyFilter's own Javadoc). Several other
  // security chains share RateLimitKeyHasher and BearerTokenHasher, but this admin-API chain is
  // the sole consumer of idempotency semantics in the whole app, so this hasher and its own
  // dedicated secret live here rather than being promoted to a shared config class.
  @Bean
  /* package */ IdempotencyKeyHasher idempotencyKeyHasher(
      @Value("${clavaris.idempotency.key-hash-secret}") final String idempotencyKeyHashSecret) {
    return new IdempotencyKeyHasher(idempotencyKeyHashSecret);
  }

  @Bean
  @Order(2)
  /* package */ SecurityFilterChain adminApiSecurityFilterChain(
      final HttpSecurity http,
      final JwtDecoder jwtDecoder,
      final RateLimiter rateLimiter,
      final RateLimitKeyHasher rateLimitKeyHasher,
      @Value("${clavaris.rate-limit.admin-api.per-client-limit:120}")
          final int adminApiPerClientLimit,
      @Value("${clavaris.rate-limit.admin-api.accounts-delete.per-client-limit:30}")
          final int accountsDeletePerClientLimit,
      @SuppressWarnings("PMD.LongVariable")
          @Value("${clavaris.rate-limit.admin-api.organizations-delete.per-client-limit:5}")
          final int organizationsDeletePerClientLimit,
      // BR-WS-04: this endpoint provisions a real Account and sends a real email — same
      // "side-effect-bearing endpoint gets its own tighter ceiling" precedent as the two limits
      // above, moderate rather than as tight as the :delete endpoints since adding members is a
      // routine, non-destructive operation.
      @SuppressWarnings("PMD.LongVariable")
          @Value("${clavaris.rate-limit.admin-api.workspace-members-write.per-client-limit:60}")
          final int workspaceMembersWritePerClientLimit,
      // SDE-III feature build, 2026-09-03 (Impersonation): the tightest ceiling on this whole
      // chain short of the two :delete endpoints — every call mints a real, usable Bearer token
      // granting full access as the target Account, so a runaway loop here is a live account-
      // takeover risk, not just a wasted write.
      @SuppressWarnings("PMD.LongVariable")
          @Value("${clavaris.rate-limit.admin-api.accounts-impersonate.per-client-limit:10}")
          final int accountsImpersonatePerClientLimit,
      // ADR-0023: resolves an accountId/workspaceId path variable to its owning Organization for
      // OrganizationClientOwnershipFilter's own one-hop routes.
      final AccountRepository accounts,
      final WorkspaceRepository workspaces,
      // Optional Idempotency-Key support — see IdempotencyKeyFilter's own Javadoc.
      final IdempotencyKeyStore idempotencyKeyStore,
      final IdempotencyKeyHasher idempotencyKeyHasher,
      final ObjectMapper objectMapper,
      final SecurityMetricsRecorder securityMetrics) {
    http.securityMatcher(ADMIN_API_PATH_PATTERN)
        .authorizeHttpRequests(new AdminApiAuthorizationRules())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
        // TD-SEC-030: anchored AFTER BearerTokenAuthenticationFilter, not before — the key
        // extractor (RateLimitIdentifiers::authenticatedPlatformClientId) reads the authenticated
        // client_id off SecurityContextHolder, which only that filter (part of the
        // oauth2ResourceServer() wiring above) populates. An unauthenticated request skips every
        // rule here (null identifier) and is rejected moments later by
        // .anyRequest().authenticated()
        // above regardless — same "malformed/absent input is skipped, not counted" convention as
        // every other AntiAbuseRateLimitingFilter rule in this codebase.
        .addFilterAfter(
            new AntiAbuseRateLimitingFilter(
                rateLimiter,
                rateLimitKeyHasher,
                AdminApiRateLimitRules.all(
                    adminApiPerClientLimit,
                    accountsDeletePerClientLimit,
                    organizationsDeletePerClientLimit,
                    workspaceMembersWritePerClientLimit,
                    accountsImpersonatePerClientLimit)),
            BearerTokenAuthenticationFilter.class)
        // ADR-0023: enforces that an OrganizationClient token can only ever reach its own bound
        // Organization — anchored after AntiAbuseRateLimitingFilter (same "needs the authenticated
        // principal already populated" reason that filter is itself anchored after
        // BearerTokenAuthenticationFilter), so it runs authenticate → rate-limit → org-ownership →
        // business logic.
        .addFilterAfter(
            new OrganizationClientOwnershipFilter(accounts, workspaces),
            AntiAbuseRateLimitingFilter.class)
        // Optional Idempotency-Key support (see IdempotencyKeyFilter's own Javadoc) — anchored
        // after OrganizationClientOwnershipFilter, last in this chain's own filter sequence: only
        // a request that already authenticated, cleared rate limiting, and is scoped to its own
        // Organization ever reaches real business logic, so idempotency bookkeeping is never spent
        // on a request that was going to be rejected anyway.
        .addFilterAfter(
            new IdempotencyKeyFilter(
                idempotencyKeyStore, idempotencyKeyHasher, objectMapper, securityMetrics),
            OrganizationClientOwnershipFilter.class)
        // Resource server, STATELESS session policy below, and securityMatcher already scopes
        // this whole chain to /api/v1/admin/** — every request reaching this point authenticates
        // via a Bearer token, never a cookie-based session, so there's no CSRF token to carry in
        // the first place. ignoringRequestMatchers(the same path already gating the chain) would
        // just restate that as a no-op condition; disabling outright says what's actually true.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

    return http.build();
  }
}
