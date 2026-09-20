package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.deleteownaccount.DeleteOwnAccountUseCase;
import com.clavaris.identity.application.usecase.deleteownaccount.SelfDeleteNotAllowedException;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationQuery;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureCommand;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureUseCase;
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
// has for its own use-case fields.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
@Controller
@RequestMapping("/o/{organizationId}/account/profile")
public class AccountProfileController {

  private static final String PROFILE_VIEW = "identity/account/profile";

  private final GetAccountForOrganizationUseCase getAccount;
  private final UpdateAccountProfilePictureUseCase updatePicture;
  private final RemoveAccountProfilePictureUseCase removePicture;
  private final DeleteOwnAccountUseCase deleteOwnAccount;
  private final CurrentAccountResolver currentAccount;

  @SuppressWarnings("java:S107")
  public AccountProfileController(
      final GetAccountForOrganizationUseCase getAccount,
      final UpdateAccountProfilePictureUseCase updatePicture,
      final RemoveAccountProfilePictureUseCase removePicture,
      final DeleteOwnAccountUseCase deleteOwnAccount,
      final CurrentAccountResolver currentAccount) {
    this.getAccount = getAccount;
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
    return "redirect:/o/" + organizationId + "/account/profile?updated";
  }

  @PostMapping("/picture/remove")
  public String removePicture(
      @PathVariable final UUID organizationId, final HttpServletRequest request) {
    removePicture.handle(new RemoveAccountProfilePictureCommand(requireCurrentAccount(request)));
    return "redirect:/o/" + organizationId + "/account/profile?removed";
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
    return "redirect:/o/" + organizationId + "/login?accountDeleted";
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
