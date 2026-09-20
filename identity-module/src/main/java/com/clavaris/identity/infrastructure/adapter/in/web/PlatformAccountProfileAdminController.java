package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.ForcePasswordResetForAccountCommand;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.ForcePasswordResetForAccountUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureCommand;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileCommand;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.InvalidProfilePictureException;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.UpdateAccountProfilePictureCommand;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.UpdateAccountProfilePictureUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * SDE-III review, 2026-09-21 — Clerk dashboard "View Profile" > Profile tab parity: name/picture
 * edits and a "Change password" action, operator-driven, on someone else's Account. Same {@link
 * PlatformAccountOrganizationAccess} organization-ownership prologue every sibling {@code
 * .../users/{accountId}/**} controller already shares ({@link PlatformAccountLifecycleController}'s
 * own Javadoc). Reuses {@link UpdateAccountProfilePictureUseCase}/{@link
 * RemoveAccountProfilePictureUseCase} (self-service's own use cases, ADR-0026) rather than
 * duplicating them — both now carry an {@code actor} field precisely so this second,
 * operator-driven caller attributes correctly instead of misclaiming the Account did this to itself
 * (SDE-III review, 2026-09-21, the same pass that added this controller).
 *
 * <p>"Change password" deliberately reuses {@link ForcePasswordResetForAccountUseCase} — the
 * account holder resets it themselves via email, never an operator typing a value the operator then
 * knows (see that command's own Javadoc for the second-caller note this pass also added).
 */
// PMD.AvoidFieldNameMatchingMethodName: updateProfile/removePicture/forcePasswordReset (the
// fields) and their same-named @PostMapping handler methods name the same real concept — same
// "the field is the collaborator, the method is the endpoint that calls it" shape every other
// controller in this codebase already has for its own use-case fields.
@SuppressWarnings({"PMD.LongVariable", "PMD.AvoidFieldNameMatchingMethodName"})
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/users/{accountId}")
public class PlatformAccountProfileAdminController {

  private static final String REDIRECT_TO_PROFILE = "redirect:/platform/dashboard/organizations/";

  private final GetAccountForOrganizationUseCase getAccount;
  private final UpdateAccountProfileUseCase updateProfile;
  private final UpdateAccountProfilePictureUseCase updatePicture;
  private final RemoveAccountProfilePictureUseCase removePicture;
  private final ForcePasswordResetForAccountUseCase forcePasswordReset;
  private final PlatformAccountOrganizationAccess organizationAccess;

  @SuppressWarnings("java:S107")
  public PlatformAccountProfileAdminController(
      final GetAccountForOrganizationUseCase getAccount,
      final UpdateAccountProfileUseCase updateProfile,
      final UpdateAccountProfilePictureUseCase updatePicture,
      final RemoveAccountProfilePictureUseCase removePicture,
      final ForcePasswordResetForAccountUseCase forcePasswordReset,
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getAccount = getAccount;
    this.updateProfile = updateProfile;
    this.updatePicture = updatePicture;
    this.removePicture = removePicture;
    this.forcePasswordReset = forcePasswordReset;
    this.organizationAccess =
        new PlatformAccountOrganizationAccess(organizationResolver, currentPlatformAccount);
  }

  @PostMapping("/profile")
  public String updateProfile(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam(required = false) final String firstName,
      @RequestParam(required = false) final String lastName) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    updateProfile.handle(
        new UpdateAccountProfileCommand(
            access.account().id(),
            blankToNull(firstName),
            blankToNull(lastName),
            actorFor(access)));
    return redirectToProfile(organizationId, accountId) + "?profileUpdated";
  }

  // PMD.OnlyOneReturn: two real, distinct outcomes — a validation error redirects with an error
  // message, success redirects plainly — same rationale AccountProfileController's own identical
  // suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping("/picture")
  public String uploadPicture(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId,
      @RequestParam("file") final MultipartFile file) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    try {
      updatePicture.handle(
          new UpdateAccountProfilePictureCommand(
              access.account().id(), readBytes(file), file.getContentType(), actorFor(access)));
    } catch (final InvalidProfilePictureException e) {
      return redirectToProfile(organizationId, accountId)
          + "?pictureError="
          + encode(e.getMessage());
    }
    return redirectToProfile(organizationId, accountId) + "?pictureUpdated";
  }

  @PostMapping("/picture/remove")
  public String removePicture(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    removePicture.handle(
        new RemoveAccountProfilePictureCommand(access.account().id(), actorFor(access)));
    return redirectToProfile(organizationId, accountId) + "?pictureRemoved";
  }

  @PostMapping("/force-password-reset")
  public String forcePasswordReset(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID accountId) {
    final PlatformAccountOrganizationAccess.ResolvedAccountAccess access =
        organizationAccess.requireOwnedAccount(request, organizationId, accountId, getAccount);
    forcePasswordReset.handle(
        new ForcePasswordResetForAccountCommand(access.account().id(), actorFor(access)));
    return redirectToProfile(organizationId, accountId) + "?passwordResetSent";
  }

  private static AuditActor actorFor(
      final PlatformAccountOrganizationAccess.ResolvedAccountAccess access) {
    return AuditActor.platformAccount(access.ownerPlatformAccountId().value());
  }

  private static String redirectToProfile(final UUID organizationId, final UUID accountId) {
    return REDIRECT_TO_PROFILE + organizationId + "/users/" + accountId;
  }

  private static String blankToNull(final String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private byte[] readBytes(final MultipartFile file) {
    try {
      return file.getBytes();
    } catch (final IOException e) {
      throw new UncheckedIOException("Failed to read the uploaded profile picture", e);
    }
  }
}
