package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithwebauthn.AuthenticateWithWebAuthnCommand;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.AuthenticateWithWebAuthnUseCase;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.InvalidWebAuthnAssertionException;
import com.clavaris.identity.application.usecase.authenticatewithwebauthn.StartWebAuthnAuthenticationUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.Account;
import com.yubico.webauthn.AssertionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TD-FUT-034, Clerk "View Profile" passkeys parity — the first JSON ({@code fetch()}-based) API
 * under the hosted-login surface, since {@code navigator.credentials.get()} is inherently a JSON
 * exchange (challenge out, assertion in), unlike every other sign-in method here, which is a plain
 * Thymeleaf form {@code POST}. {@code @RestController}, not {@code @Controller} +
 * {@code @ResponseBody}: this whole class is JSON, with no page of its own to render — the "Sign in
 * with a passkey" button itself lives on {@code login.html}, feature-detected and wired up by
 * {@code webauthn-login.js}.
 *
 * <p>Discoverable/resident-key ("usernameless") flow only — see {@code
 * StartWebAuthnAuthenticationService}'s own Javadoc. {@code /start} has no request body; {@code
 * /finish} reuses {@link PrimaryFactorLoginCompletion#completeAfterPrimaryFactor} (the same
 * DeviceTrustGate→SessionTaskGate→AuthenticatedSessionCompletion sequence {@link LoginController}/
 * {@link UsernameSignInController} already share) and strips its {@code "redirect:"} prefix, since
 * a JSON response carries the next URL as data for the client's own {@code window.location}
 * navigation, not a server-side redirect.
 */
// PMD.LongVariable: startAuthentication/requestDeviceTrustChallenge/authenticationPolicyProvider/
// redirectUrlResolver name exactly what they hold — same precedent LoginController/
// UsernameSignInController's own identical suppression already establishes for these same names.
// PMD.LawOfDemeter: request.getSession() is the standard Servlet API shape — same rationale
// AntiAbuseRateLimitingFilter's own response.getWriter() suppression already documents.
// PMD.OnlyOneReturn: each flagged method has multiple genuinely distinct outcomes (a 400 error
// body vs. the real success body) — same "each outcome needs its own exit" rationale as
// SetRateLimitPolicyController's own identical suppression.
@SuppressWarnings({"PMD.LongVariable", "PMD.LawOfDemeter", "PMD.OnlyOneReturn"})
@RestController
@RequestMapping("/o/{organizationId}/login/webauthn")
public class WebAuthnSignInController {

  private final StartWebAuthnAuthenticationUseCase startAuthentication;
  private final AuthenticateWithWebAuthnUseCase authenticate;
  private final PrimaryFactorLoginPorts loginPorts;

  @SuppressWarnings("java:S107")
  public WebAuthnSignInController(
      final StartWebAuthnAuthenticationUseCase startAuthentication,
      final AuthenticateWithWebAuthnUseCase authenticate,
      final KnownDeviceRepository knownDevices,
      final RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge,
      final AccountAuthenticationPolicyProvider authenticationPolicyProvider,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final RecordLoginEventUseCase recordLoginEvent) {
    this.startAuthentication = startAuthentication;
    this.authenticate = authenticate;
    this.loginPorts =
        new PrimaryFactorLoginPorts(
            knownDevices,
            requestDeviceTrustChallenge,
            authenticationPolicyProvider,
            sessions,
            recordLoginDevice,
            redirectUrlResolver,
            recordLoginEvent);
  }

  @Operation(summary = "Start a discoverable (usernameless) WebAuthn passkey sign-in (TD-FUT-034)")
  @ApiResponse(
      responseCode = "200",
      description = "WebAuthn assertion request, as JSON for navigator.credentials.get()")
  @PostMapping(value = "/start", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> start(
      @PathVariable final UUID organizationId, final HttpServletRequest request)
      throws IOException {
    final AssertionRequest assertionRequest = startAuthentication.handle();
    request
        .getSession()
        .setAttribute(WebAuthnAuthenticationPendingState.ATTRIBUTE, assertionRequest.toJson());
    return ResponseEntity.ok(assertionRequest.toCredentialsGetJson());
  }

  @Operation(summary = "Complete a WebAuthn passkey sign-in with the browser's own assertion")
  @ApiResponse(responseCode = "200", description = "Signed in — body carries the next redirect URL")
  @ApiResponse(
      responseCode = "400",
      description = "No pending sign-in, or an invalid/expired assertion")
  @PostMapping("/finish")
  public ResponseEntity<Map<String, String>> finish(
      @PathVariable final UUID organizationId,
      @RequestBody final WebAuthnFinishSignInRequest body,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {
    final HttpSession session = request.getSession(false);
    final String pending =
        session == null
            ? null
            : (String) session.getAttribute(WebAuthnAuthenticationPendingState.ATTRIBUTE);
    if (pending == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "No pending passkey sign-in"));
    }
    session.removeAttribute(WebAuthnAuthenticationPendingState.ATTRIBUTE);

    final AssertionRequest assertionRequest = AssertionRequest.fromJson(pending);
    final Account account;
    try {
      account =
          authenticate.handle(
              new AuthenticateWithWebAuthnCommand(assertionRequest, body.credential()));
    } catch (final InvalidWebAuthnAssertionException _) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "Invalid or expired passkey sign-in attempt"));
    }

    final String redirectDirective =
        PrimaryFactorLoginCompletion.completeAfterPrimaryFactor(
            loginPorts,
            request,
            response,
            organizationId,
            account,
            PendingAuthenticationFactor.PASSKEY,
            body.clientId(),
            body.redirectUrl());
    return ResponseEntity.ok(Map.of("redirectTo", stripRedirectPrefix(redirectDirective)));
  }

  private static String stripRedirectPrefix(final String redirectDirective) {
    final String prefix = "redirect:";
    return redirectDirective.startsWith(prefix)
        ? redirectDirective.substring(prefix.length())
        : redirectDirective;
  }
}
