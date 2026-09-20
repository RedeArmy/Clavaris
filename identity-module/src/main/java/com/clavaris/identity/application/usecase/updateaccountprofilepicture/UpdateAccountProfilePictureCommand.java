package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;
import java.util.Arrays;
import java.util.Objects;

/**
 * Input to {@link UpdateAccountProfilePictureUseCase}. {@code content}/{@code contentType} are the
 * raw uploaded bytes and the browser-reported MIME type — validated by {@link
 * ProfilePictureValidator} against its own 10MB cap (the pasted "Update profile" UI copy's own
 * stated limit) and a fixed image-format allow-list, never trusted as pre-validated by the caller.
 *
 * @param actor SDE-III review, 2026-09-21 — who actually performed this upload: the Account itself
 *     (self-service, {@code AccountProfileController}) or an operator acting on someone else's
 *     behalf (dashboard "View Profile" > Profile tab, {@code
 *     PlatformAccountProfileAdminController}). Previously hardcoded to {@code
 *     AuditActor.account(...)} inside {@link UpdateAccountProfilePictureService} itself — a real
 *     bug once a second, operator-driven caller existed: the audit trail would have claimed the
 *     Account did this to itself even when an operator actually did.
 */
public record UpdateAccountProfilePictureCommand(
    AccountId accountId, byte[] content, String contentType, AuditActor actor) {

  /**
   * Self-service shape, kept so {@code AccountProfileController} stays unchanged — defaults {@code
   * actor} to {@code AuditActor.account(accountId)}, the only correct value for a genuinely
   * self-service upload.
   */
  public UpdateAccountProfilePictureCommand(
      final AccountId accountId, final byte[] content, final String contentType) {
    this(accountId, content, contentType, AuditActor.account(accountId.value()));
  }

  // SonarCloud: a record's auto-generated equals/hashCode/toString use byte[] content's own
  // identity, not its bytes — overridden here so two commands with the same upload actually
  // compare equal, same convention StoredProfilePicture's own identical override establishes.
  // toString prints the array's length, not Arrays.toString(byte[])'s own raw byte dump.
  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    if (!(other
        instanceof
        UpdateAccountProfilePictureCommand(
            AccountId otherAccountId,
            byte[] otherContent,
            String otherContentType,
            AuditActor otherActor))) {
      return false;
    }
    return accountId.equals(otherAccountId)
        && Arrays.equals(content, otherContent)
        && contentType.equals(otherContentType)
        && actor.equals(otherActor);
  }

  @Override
  public int hashCode() {
    return Objects.hash(accountId, Arrays.hashCode(content), contentType, actor);
  }

  @Override
  public String toString() {
    return "UpdateAccountProfilePictureCommand[accountId="
        + accountId
        + ", content=byte["
        + content.length
        + "], contentType="
        + contentType
        + ", actor="
        + actor
        + "]";
  }
}
