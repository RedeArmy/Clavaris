package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.deletewebauthncredential.DeleteWebAuthnCredentialCommand;
import com.clavaris.identity.application.usecase.deletewebauthncredential.DeleteWebAuthnCredentialUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationQuery;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountQuery;
import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountUseCase;
import com.clavaris.identity.application.usecase.registerwebauthncredential.CompleteWebAuthnRegistrationCommand;
import com.clavaris.identity.application.usecase.registerwebauthncredential.CompleteWebAuthnRegistrationUseCase;
import com.clavaris.identity.application.usecase.registerwebauthncredential.InvalidWebAuthnRegistrationException;
import com.clavaris.identity.application.usecase.registerwebauthncredential.StartWebAuthnRegistrationCommand;
import com.clavaris.identity.application.usecase.registerwebauthncredential.StartWebAuthnRegistrationUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * TD-FUT-034, Clerk "View Profile" passkeys parity — the self-service "your passkeys" page, same
 * shape/{@code organizationId}-is-cosmetic rationale as {@link AccountSessionsController}'s own
 * Javadoc. {@code @Controller} + {@code @ResponseBody} on the two JSON ceremony endpoints, not
 * {@code @RestController}: same deliberate mixed shape {@link AccountAvatarController} already
 * documents (a hosted-UI page that happens to need JSON for two of its own actions, not a
 * management-API controller).
 */
// PMD.AvoidFieldNameMatchingMethodName: startRegistration (the field) and startRegistration()
// (the @PostMapping handler) name the same real concept — same "the field is the collaborator,
// the method is the endpoint that calls it" shape AccountProfileController's own identical
// suppression already documents. PMD.LongVariable: completeRegistration names exactly what it
// holds. PMD.ExcessiveImports: this controller genuinely orchestrates five collaborating use
// cases plus their own command/query/exception types — same "one import per collaborating type is
// inherent to the design" rationale this codebase's other controllers already establish.
// PMD.LawOfDemeter: request.getSession() is the standard Servlet API shape, same rationale as
// every other controller touching HttpSession directly. PMD.OnlyOneReturn: each flagged method has
// multiple genuinely distinct outcomes (a 400/404 error vs. the real success response).
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.LongVariable",
  "PMD.ExcessiveImports",
  "PMD.LawOfDemeter",
  "PMD.OnlyOneReturn"
})
@Controller
@RequestMapping("/o/{organizationId}/account/passkeys")
public class AccountWebAuthnCredentialsController {

  private static final String PASSKEYS_VIEW = "identity/account/passkeys";

  private final StartWebAuthnRegistrationUseCase startRegistration;
  private final CompleteWebAuthnRegistrationUseCase completeRegistration;
  private final ListWebAuthnCredentialsForAccountUseCase listCredentials;
  private final DeleteWebAuthnCredentialUseCase deleteCredential;
  private final GetAccountForOrganizationUseCase getAccount;
  private final CurrentAccountResolver currentAccount;
  private final RequireRecentAuthentication requireRecentAuthentication;

  @SuppressWarnings("java:S107")
  public AccountWebAuthnCredentialsController(
      final StartWebAuthnRegistrationUseCase startRegistration,
      final CompleteWebAuthnRegistrationUseCase completeRegistration,
      final ListWebAuthnCredentialsForAccountUseCase listCredentials,
      final DeleteWebAuthnCredentialUseCase deleteCredential,
      final GetAccountForOrganizationUseCase getAccount,
      final CurrentAccountResolver currentAccount,
      final RequireRecentAuthentication requireRecentAuthentication) {
    this.startRegistration = startRegistration;
    this.completeRegistration = completeRegistration;
    this.listCredentials = listCredentials;
    this.deleteCredential = deleteCredential;
    this.getAccount = getAccount;
    this.currentAccount = currentAccount;
    this.requireRecentAuthentication = requireRecentAuthentication;
  }

  @GetMapping
  public String show(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId = requireCurrentAccount(request);
    model.addAttribute(
        "credentials",
        listCredentials.handle(new ListWebAuthnCredentialsForAccountQuery(accountId)));
    model.addAttribute("organizationId", organizationId);
    return PASSKEYS_VIEW;
  }

  @PostMapping(value = "/registration/start", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<String> startRegistration(
      @PathVariable final UUID organizationId, final HttpServletRequest request)
      throws IOException {
    final AccountId accountId = requireCurrentAccount(request);
    final Account account = requireAccount(organizationId, accountId);
    final PublicKeyCredentialCreationOptions options =
        startRegistration.handle(
            new StartWebAuthnRegistrationCommand(
                accountId, new OrganizationId(organizationId), account.email().value()));
    request.getSession().setAttribute(WebAuthnRegistrationPendingState.ATTRIBUTE, options.toJson());
    return ResponseEntity.ok(options.toCredentialsCreateJson());
  }

  @PostMapping("/registration/finish")
  @ResponseBody
  public ResponseEntity<Map<String, String>> finishRegistration(
      @PathVariable final UUID organizationId,
      @RequestBody final WebAuthnFinishRegistrationRequest body,
      final HttpServletRequest request)
      throws IOException {
    final AccountId accountId = requireCurrentAccount(request);
    final String pending =
        (String) request.getSession().getAttribute(WebAuthnRegistrationPendingState.ATTRIBUTE);
    if (pending == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "No pending passkey registration"));
    }
    request.getSession().removeAttribute(WebAuthnRegistrationPendingState.ATTRIBUTE);

    final PublicKeyCredentialCreationOptions options =
        PublicKeyCredentialCreationOptions.fromJson(pending);
    try {
      completeRegistration.handle(
          new CompleteWebAuthnRegistrationCommand(
              accountId,
              new OrganizationId(organizationId),
              options,
              body.credential(),
              body.nickname()));
    } catch (final InvalidWebAuthnRegistrationException _) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(Map.of("error", "Passkey registration failed"));
    }
    return ResponseEntity.noContent().build();
  }

  // Deliberately a no-op either way (not found, or belongs to a different Account) — same
  // "ownership mismatch is a safe no-op, never an error" posture DeleteWebAuthnCredentialUseCase's
  // own Javadoc establishes.
  //
  // Clerk "Sessions" settings parity: removing a security credential is exactly the kind of
  // sensitive action the reverification window exists to gate — RequireRecentAuthentication's own
  // Javadoc has the full mechanism. A stale authentication is sent back to login rather than
  // silently completing the deletion; the retry-the-action-after-re-login UX is deliberately simple
  // for this first real consumer (no pending-action resume mechanism yet) — a natural follow-up,
  // not assumed solved here.
  @PostMapping("/{credentialId}/delete")
  public String delete(
      @PathVariable final UUID organizationId,
      @PathVariable final UUID credentialId,
      final HttpServletRequest request) {
    final AccountId accountId = requireCurrentAccount(request);
    if (requireRecentAuthentication.isStale(organizationId)) {
      return "redirect:/o/" + organizationId + "/login";
    }
    deleteCredential.handle(new DeleteWebAuthnCredentialCommand(credentialId, accountId));
    return "redirect:/o/" + organizationId + "/account/passkeys";
  }

  private Account requireAccount(final UUID organizationId, final AccountId accountId) {
    return getAccount
        .handle(new GetAccountForOrganizationQuery(new OrganizationId(organizationId), accountId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private AccountId requireCurrentAccount(final HttpServletRequest request) {
    return CurrentSessionSupport.requireResolved(currentAccount.resolve(request), "Account");
  }
}
