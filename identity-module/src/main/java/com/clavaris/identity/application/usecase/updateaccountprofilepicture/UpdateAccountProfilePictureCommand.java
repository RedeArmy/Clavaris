package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.BinaryContentEquality;
import com.clavaris.identity.domain.model.AccountId;
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
  // identity, not its bytes — overridden here via BinaryContentEquality so two commands with the
  // same upload actually compare equal, same shared helper every other byte[]-content record
  // across this module uses.
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
        && BinaryContentEquality.contentEquals(content, contentType, otherContent, otherContentType)
        && actor.equals(otherActor);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        accountId, BinaryContentEquality.contentHashCode(content, contentType), actor);
  }

  @Override
  public String toString() {
    return "UpdateAccountProfilePictureCommand[accountId="
        + accountId
        + ", content="
        + BinaryContentEquality.describeContentLength(content)
        + ", contentType="
        + contentType
        + ", actor="
        + actor
        + "]";
  }
}
