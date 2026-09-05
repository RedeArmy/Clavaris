package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * Outbound port — implemented in {@code app}, using real Spring Security machinery ({@code
 * SecurityContextRepository}, {@code RequestCache}) that identity-module deliberately does not
 * depend on (this module only pulls in {@code spring-security-crypto} for Argon2, never the full
 * {@code spring-security-config} stack — that belongs to the module that owns the actual {@code
 * SecurityFilterChain} wiring). Same module-independence rationale as {@code
 * OrganizationExistsChecker}/{@code SigningKeyProvisioner} in the other business modules.
 *
 * <p>Only {@link LoginController} calls this — after {@code AuthenticateWithPasswordUseCase}
 * succeeds, this is what turns "we know which Account this is" into "the browser now carries a real
 * authenticated session," so the redirected-to {@code /oauth2/authorize} request (or whatever
 * protected URL triggered the login redirect in the first place) sees an authenticated principal.
 */
// No longer @FunctionalInterface — ADR-0020 added establishViaSocialLogin as a second abstract
// method, both implemented by the same SpringSecurityAuthenticatedSessionEstablisher class.
public interface AuthenticatedSessionEstablisher {

  /**
   * @param fallbackUrl where to send the browser if there was no in-flight protected request to
   *     return to (e.g. the user navigated to the login page directly, not via a redirect from
   *     {@code /oauth2/authorize}).
   * @return the URL the browser should be redirected to next.
   */
  String establish(
      HttpServletRequest request, HttpServletResponse response, UUID accountId, String fallbackUrl);

  /**
   * ADR-0020: same contract as {@link #establish}, for a session established via {@code
   * AuthenticateWithSocialProviderUseCase} rather than a password — {@code provider} is what lets
   * the implementation mark the resulting {@code Authentication} with the actual mechanism used, so
   * {@code AuthenticationContextClaimsCustomizer} can compute a real OIDC {@code amr} claim instead
   * of always hardcoding {@code ["pwd"]}.
   */
  String establishViaSocialLogin(
      HttpServletRequest request,
      HttpServletResponse response,
      UUID accountId,
      SocialProvider provider,
      String fallbackUrl);

  /**
   * ADR-0024 §3: same contract as {@link #establish}, for a session established via a passwordless
   * email proof — {@code authenticatewithemailcode}/{@code authenticatewithemaillink} both call
   * this same method, since both represent the identical OIDC {@code amr=["otp"]} factor (a
   * single-use value proven once, never a stored, reusable credential) — see the implementation's
   * own Javadoc for the exact {@code FactorGrantedAuthority}/AMR authorities this adds.
   */
  String establishViaOneTimeEmailProof(
      HttpServletRequest request, HttpServletResponse response, UUID accountId, String fallbackUrl);
}
