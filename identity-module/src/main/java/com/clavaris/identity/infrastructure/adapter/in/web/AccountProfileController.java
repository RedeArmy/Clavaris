package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.deleteownaccount.DeleteOwnAccountUseCase;
import com.clavaris.identity.application.usecase.deleteownaccount.SelfDeleteNotAllowedException;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationQuery;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureCommand;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileCommand;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.InvalidProfilePictureException;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.UpdateAccountProfilePictureCommand;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.UpdateAccountProfilePictureUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * ADR-0026: the self-service "Update profile" page — the pasted "Recommended size 1:1, up to 10MB"
 * UI copy this whole feature implements. Same shape as {@link AccountSessionsController} (its own
 * Javadoc's reasoning applies verbatim here): {@code organizationId} in the path is cosmetic only,
 * every query and mutation is scoped by the resolved {@link AccountId} from the security context;
 * {@code app}'s own {@code OrganizationAuthorizationServerConfig} already covers {@code
 * /o/*&#47;account/**} with {@code ROLE_ACCOUNT} — no new security config needed for this route.
 */
// PMD.AvoidFieldNameMatchingMethodName: removePicture (the field) and removePicture() (the
// @PostMapping handler) name the same real concept — same "the field is the collaborator, the
// method is the endpoint that calls it" shape every other controller in this codebase already
// has for its own use-case fields. PMD.ExcessiveImports: TD-FUT-040 added updateProfile's own
// exception/command/use-case imports on top of what this controller already had — same
// "real use cases, not a God-class symptom" reasoning PlatformAccountProfileAdminController's
// own, slightly larger, identical surface already establishes.
// PMD.TooManyMethods: real use-case handlers plus the small redirectToProfile/
// redirectWithEmbedParams/appendEmbedParamsIfPresent trio the 2026-10-10 bug fix added (see this
// class's own DISPLAY_PARAM field comment) — one shared helper for a real, repeated need across
// five distinct redirects, not organic sprawl.
@SuppressWarnings({
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods"
})
@Controller
@RequestMapping("/o/{organizationId}/account/profile")
public class AccountProfileController {

  private static final String PROFILE_VIEW = "identity/account/profile";
  private static final String REDIRECT_PREFIX = "redirect:/o/";

  // Real bug found live, 2026-10-10: every one of this controller's own post-mutation redirects
  // used to drop these two — the ADR-0009 §1/§4 embedded-iframe query-param convention
  // ContentSecurityPolicyHeaderWriter's own frame-ancestors relaxation reads on every GET to this
  // same page — so the very next request after a successful Save/upload/remove/delete landed back
  // on this page with the default, un-relaxed frame-ancestors 'none', and the browser refused to
  // keep displaying it inside the iframe the request originally came from (live-observed as a
  // blocked-frame error page immediately after clicking "Guardar"). Same param names
  // ContentSecurityPolicyHeaderWriter's own CLIENT_ID_PARAM/DISPLAY_PARAM/DISPLAY_MODAL already use
  // — not shared across the module boundary (app/identity-module), so duplicated here rather than
  // introducing a dependency neither module needs for anything else.
  private static final String DISPLAY_PARAM = "display";
  private static final String DISPLAY_MODAL = "modal";
  private static final String CLIENT_ID_PARAM = "clientId";

  private final GetAccountForOrganizationUseCase getAccount;
  private final UpdateAccountProfileUseCase updateProfile;
  private final UpdateAccountProfilePictureUseCase updatePicture;
  private final RemoveAccountProfilePictureUseCase removePicture;
  private final DeleteOwnAccountUseCase deleteOwnAccount;
  private final CurrentAccountResolver currentAccount;

  @SuppressWarnings("java:S107")
  public AccountProfileController(
      final GetAccountForOrganizationUseCase getAccount,
      final UpdateAccountProfileUseCase updateProfile,
      final UpdateAccountProfilePictureUseCase updatePicture,
      final RemoveAccountProfilePictureUseCase removePicture,
      final DeleteOwnAccountUseCase deleteOwnAccount,
      final CurrentAccountResolver currentAccount) {
    this.getAccount = getAccount;
    this.updateProfile = updateProfile;
    this.updatePicture = updatePicture;
    this.removePicture = removePicture;
    this.deleteOwnAccount = deleteOwnAccount;
    this.currentAccount = currentAccount;
  }

  @GetMapping
  public String show(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    populateModel(model, organizationId, requireCurrentAccount(request));
    return PROFILE_VIEW;
  }

  // TD-FUT-040: self-service name/username/phone editing — same shape as
  // PlatformAccountProfileAdminController's own identical operator-driven method, reusing the exact
  // same UpdateAccountProfileUseCase (built generically enough for this call site from day one, per
  // that command's own Javadoc) rather than a second, parallel implementation. Actor is
  // AuditActor.account(...) here, never platformAccount(...) — the Account is genuinely acting on
  // itself, unlike the admin controller's own operator-driven call.
  //
  // PMD.OnlyOneReturn: two real, distinct outcomes — a username conflict re-renders the form with
  // an error, success redirects plainly — same rationale PlatformAccountProfileAdminController's
  // own identical suppression documents.
  @SuppressWarnings({"PMD.OnlyOneReturn", "java:S107"})
  @PostMapping
  public String updateProfile(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      @RequestParam(required = false) final String firstName,
      @RequestParam(required = false) final String lastName,
      @RequestParam(required = false) final String username,
      @RequestParam(required = false) final String phoneCountryCode,
      @RequestParam(required = false) final String phoneNumberLocal) {
    final AccountId accountId = requireCurrentAccount(request);
    try {
      updateProfile.handle(
          new UpdateAccountProfileCommand(
              accountId,
              blankToNull(firstName),
              blankToNull(lastName),
              blankToNull(username),
              PhoneNumberInput.combine(phoneCountryCode, phoneNumberLocal),
              AuditActor.account(accountId.value())));
    } catch (final UsernameAlreadyRegisteredException _) {
      return redirectToProfile(
          request, organizationId, "usernameError=" + encode("This username is already taken"));
    }
    return redirectToProfile(request, organizationId, "profileUpdated");
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  // See this class's own DISPLAY_PARAM field comment for the real bug this closes — every
  // redirect this controller issues must preserve the embedded-iframe query-param convention the
  // request that triggered it already carried, or the very next load of the target page breaks
  // out of its own iframe.
  private static String redirectToProfile(
      final HttpServletRequest request, final UUID organizationId, final String statusQueryParam) {
    return redirectWithEmbedParams(request, "/account/profile?" + statusQueryParam, organizationId);
  }

  private static String redirectWithEmbedParams(
      final HttpServletRequest request, final String pathAndQuery, final UUID organizationId) {
    final StringBuilder url =
        new StringBuilder(REDIRECT_PREFIX).append(organizationId).append(pathAndQuery);
    appendEmbedParamsIfPresent(request, url);
    return url.toString();
  }

  private static void appendEmbedParamsIfPresent(
      final HttpServletRequest request, final StringBuilder url) {
    if (!DISPLAY_MODAL.equals(request.getParameter(DISPLAY_PARAM))) {
      return;
    }
    url.append('&').append(DISPLAY_PARAM).append('=').append(DISPLAY_MODAL);
    final String clientId = request.getParameter(CLIENT_ID_PARAM);
    if (clientId != null) {
      url.append('&').append(CLIENT_ID_PARAM).append('=').append(encode(clientId));
    }
  }

  // PMD.OnlyOneReturn: two real, distinct outcomes — a validation error re-renders the form,
  // success redirects — same "each outcome needs its own exit" rationale
  // SetRateLimitPolicyController's own identical suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/picture")
  public String uploadPicture(
      @PathVariable final UUID organizationId,
      @RequestParam("file") final MultipartFile file,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId = requireCurrentAccount(request);
    try {
      updatePicture.handle(
          new UpdateAccountProfilePictureCommand(
              accountId, readBytes(file), file.getContentType()));
    } catch (final InvalidProfilePictureException e) {
      populateModel(model, organizationId, accountId);
      model.addAttribute("uploadError", e.getMessage());
      return PROFILE_VIEW;
    }
    return redirectToProfile(request, organizationId, "updated");
  }

  @PostMapping("/picture/remove")
  public String removePicture(
      @PathVariable final UUID organizationId, final HttpServletRequest request) {
    removePicture.handle(new RemoveAccountProfilePictureCommand(requireCurrentAccount(request)));
    return redirectToProfile(request, organizationId, "removed");
  }

  // PMD.OnlyOneReturn: two real, distinct outcomes — not allowed re-renders with an error, success
  // ends the session and redirects to login — same rationale this class's own uploadPicture
  // suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/delete")
  public String delete(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    final AccountId accountId = requireCurrentAccount(request);
    try {
      deleteOwnAccount.handle(accountId);
    } catch (final SelfDeleteNotAllowedException _) {
      populateModel(model, organizationId, accountId);
      model.addAttribute("deleteError", true);
      return PROFILE_VIEW;
    }
    // The domain-level delete already revoked this Account's own sessions at the store level
    // (DeleteAccountService's own AccountSessionRevoker) — invalidating the local HttpSession too
    // is belt-and-suspenders for this exact request/response cycle, same discipline every other
    // "this credential is now gone" action in this codebase already takes.
    final HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    return redirectWithEmbedParams(request, "/login?accountDeleted", organizationId);
  }

  private void populateModel(
      final Model model, final UUID organizationId, final AccountId accountId) {
    final Account account =
        getAccount
            .handle(
                new GetAccountForOrganizationQuery(new OrganizationId(organizationId), accountId))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Authenticated Account "
                            + accountId
                            + " not found in its own Organization"));
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("account", account);
  }

  // Not expected to ever actually be empty — app's own security chain guarantees an authenticated
  // tenant Account before this controller runs — see AccountSessionsController's own identical
  // rationale/CurrentSessionSupport#requireResolved's own Javadoc.
  private AccountId requireCurrentAccount(final HttpServletRequest request) {
    return CurrentSessionSupport.requireResolved(currentAccount.resolve(request), "Account");
  }

  // MultipartFile#getBytes() declares a checked IOException Spring MVC handler methods don't
  // otherwise need to propagate — same "read once into memory up front" shape
  // CachedBodyHttpServletRequest's own constructor already establishes for a request body.
  private byte[] readBytes(final MultipartFile file) {
    try {
      return file.getBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to read the uploaded profile picture", e);
    }
  }
}
