package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.domain.model.PlatformScopes;
import com.clavaris.identity.infrastructure.adapter.out.security.PlatformSigningKeyMaterial;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

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
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@Configuration
class AdminApiSecurityConfig {

  // Spring Security's own fixed prefix for a JWT scope-derived GrantedAuthority — one constant,
  // not five repeated literals that could silently drift apart (PMD.AvoidDuplicateLiterals).
  @SuppressWarnings("PMD.LongVariable")
  private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

  @SuppressWarnings("PMD.LongVariable")
  private static final String ADMIN_API_PATH_PATTERN = "/api/v1/admin/**";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AdminApiSecurityConfig() {
    // Intentionally empty — this class holds no state, only the @Bean methods below.
  }

  @Bean
  /* package */ JwtDecoder platformJwtDecoder(final PlatformSigningKeyMaterial platformKey) {
    return NimbusJwtDecoder.withPublicKey((RSAPublicKey) platformKey.keyPair().getPublic()).build();
  }

  @Bean
  @Order(2)
  /* package */ SecurityFilterChain adminApiSecurityFilterChain(
      final HttpSecurity http,
      final JwtDecoder jwtDecoder,
      final RateLimiter rateLimiter,
      @SuppressWarnings("PMD.LongVariable") final RateLimitKeyHasher rateLimitKeyHasher,
      @SuppressWarnings("PMD.LongVariable")
          @Value("${clavaris.rate-limit.admin-api.per-client-limit:120}")
          final int adminApiPerClientLimit,
      @SuppressWarnings("PMD.LongVariable")
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
          final int workspaceMembersWritePerClientLimit) {
    http.securityMatcher(ADMIN_API_PATH_PATTERN)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    // BR-ORG-06: creating an Organization is gated by its own scope, not just
                    // "any valid platform token" — defence in depth beyond BR-PLATFORM-02's
                    // blanket platform-tier-only rule, matching the scope namespace
                    // PlatformScopes already reserves for this exact action.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ORGANIZATIONS_WRITE)
                    // ADR-0010 §6.2: tuning the capacity-layer ceiling is its own scope too, same
                    // defence-in-depth reasoning as Organization creation above — a platform
                    // token that can create Organizations doesn't automatically get to also
                    // change a running one's rate-limit ceiling.
                    .requestMatchers(
                        HttpMethod.PUT, "/api/v1/admin/organizations/*/rate-limit-policy")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.RATE_LIMIT_POLICY_WRITE)
                    // TD-SEC-008/ADR-0010 §5.2: manually-triggered key rotation is its own scope
                    // too, same defence-in-depth reasoning as the two rules above.
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/admin/organizations/*/signing-keys/rotate")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SIGNING_KEYS_ROTATE)
                    // TD-SEC-018: rotating/revoking a PlatformClient — its own scopes too, same
                    // defence-in-depth reasoning as every other admin-API rule above.
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/admin/platform-clients/*/rotate-secret")
                    .hasAuthority(
                        SCOPE_AUTHORITY_PREFIX + PlatformScopes.PLATFORM_CLIENTS_ROTATE_SECRET)
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/platform-clients/*/revoke")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.PLATFORM_CLIENTS_REVOKE)
                    // BR-DATA-02: hard-deleting an Account is its own scope too, same
                    // defence-in-depth reasoning as every other admin-API rule above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:delete")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_DELETE)
                    // Reversible ban/unban — one shared scope for both directions, see
                    // PlatformScopes.ACCOUNTS_SUSPEND's own Javadoc for why.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:suspend")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_SUSPEND)
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:reactivate")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_SUSPEND)
                    // BR-DATA-02/03: hard-deleting an entire Organization — its own scope too,
                    // deliberately separate from ACCOUNTS_DELETE (an entire tenant's whole
                    // account pool vs. one identity), arguably the single most irreversible
                    // action this whole surface exposes.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*:delete")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ORGANIZATIONS_DELETE)
                    // BR-WS: creating a Workspace — its own scope, same defence-in-depth
                    // reasoning as every other admin-API rule above.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*/workspaces")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACES_WRITE)
                    // BR-WS-04/05: adding a member or changing an existing member's role —
                    // grouped under one scope, see PlatformScopes.WORKSPACE_MEMBERS_WRITE's own
                    // Javadoc for why.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/workspaces/*/members")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_MEMBERS_WRITE)
                    .requestMatchers(HttpMethod.PUT, "/api/v1/admin/workspaces/*/members/*/role")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_MEMBERS_WRITE)
                    // BR-WS-03: removing a member — its own scope, deliberately separate from
                    // WORKSPACE_MEMBERS_WRITE, same defence-in-depth reasoning as ACCOUNTS_DELETE.
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/workspaces/*/members/*:remove")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_MEMBERS_REMOVE)
                    // ADR-0020 Decision 3, BR-ID-12: turning per-Organization social login on/off
                    // and choosing its providers — its own scope, same defence-in-depth reasoning
                    // as every other admin-API rule above.
                    .requestMatchers(
                        HttpMethod.PUT, "/api/v1/admin/organizations/*/social-login-policy")
                    .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SOCIAL_LOGIN_POLICY_WRITE)
                    .anyRequest()
                    .authenticated())
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
                List.of(
                    new RateLimitRule(
                        "admin-api-post:client",
                        HttpMethod.POST,
                        ADMIN_API_PATH_PATTERN,
                        RateLimitRule.always(),
                        RateLimitIdentifiers::authenticatedPlatformClientId,
                        adminApiPerClientLimit,
                        Duration.ofMinutes(1)),
                    new RateLimitRule(
                        "admin-api-put:client",
                        HttpMethod.PUT,
                        ADMIN_API_PATH_PATTERN,
                        RateLimitRule.always(),
                        RateLimitIdentifiers::authenticatedPlatformClientId,
                        adminApiPerClientLimit,
                        Duration.ofMinutes(1)),
                    // BR-DATA-02: individual accounts — a routine call for a consuming
                    // application's own backend (e.g. per-user deletion requests), so this ceiling
                    // stays well above the blanket rule above rather than redundantly tighter.
                    new RateLimitRule(
                        "admin-api-accounts-delete:client",
                        HttpMethod.POST,
                        "/api/v1/admin/accounts/*:delete",
                        RateLimitRule.always(),
                        RateLimitIdentifiers::authenticatedPlatformClientId,
                        accountsDeletePerClientLimit,
                        Duration.ofMinutes(5)),
                    // BR-DATA-02/03: an entire Organization's whole account pool — the single most
                    // irreversible call this surface exposes (this file's own scope-check comment
                    // above), so this is the tightest ceiling on this whole chain, deliberately.
                    new RateLimitRule(
                        "admin-api-organizations-delete:client",
                        HttpMethod.POST,
                        "/api/v1/admin/organizations/*:delete",
                        RateLimitRule.always(),
                        RateLimitIdentifiers::authenticatedPlatformClientId,
                        organizationsDeletePerClientLimit,
                        Duration.ofMinutes(5)),
                    // BR-WS-04: a real Account + a real outbound email per call — its own tighter
                    // ceiling than the blanket admin-api-post:client rule above, same
                    // "side-effect-bearing endpoint" precedent as the two :delete rules.
                    new RateLimitRule(
                        "admin-api-workspace-members-write:client",
                        HttpMethod.POST,
                        "/api/v1/admin/workspaces/*/members",
                        RateLimitRule.always(),
                        RateLimitIdentifiers::authenticatedPlatformClientId,
                        workspaceMembersWritePerClientLimit,
                        Duration.ofMinutes(5)))),
            BearerTokenAuthenticationFilter.class)
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
