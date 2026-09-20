package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.domain.model.AccountId;
import org.junit.jupiter.api.Test;

class UpdateAccountProfilePictureCommandTest {

  private static final AccountId ACCOUNT_ID = AccountId.newId();
  private static final AuditActor ACTOR = AuditActor.account(ACCOUNT_ID.value());

  @Test
  void selfServiceConstructorDefaultsTheActorToTheAccountItself() {
    UpdateAccountProfilePictureCommand command =
        new UpdateAccountProfilePictureCommand(ACCOUNT_ID, new byte[] {1}, "image/png");

    assertThat(command.actor()).isEqualTo(AuditActor.account(ACCOUNT_ID.value()));
  }

  @Test
  void isEqualToAnotherInstanceWithTheSameFieldsIncludingBytes() {
    UpdateAccountProfilePictureCommand first =
        new UpdateAccountProfilePictureCommand(
            ACCOUNT_ID, new byte[] {1, 2, 3}, "image/png", ACTOR);
    UpdateAccountProfilePictureCommand second =
        new UpdateAccountProfilePictureCommand(
            ACCOUNT_ID, new byte[] {1, 2, 3}, "image/png", ACTOR);

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void isNotEqualWhenBytesDiffer() {
    UpdateAccountProfilePictureCommand first =
        new UpdateAccountProfilePictureCommand(
            ACCOUNT_ID, new byte[] {1, 2, 3}, "image/png", ACTOR);
    UpdateAccountProfilePictureCommand second =
        new UpdateAccountProfilePictureCommand(
            ACCOUNT_ID, new byte[] {9, 9, 9}, "image/png", ACTOR);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isNotEqualWhenAccountIdContentTypeOrActorDiffer() {
    UpdateAccountProfilePictureCommand base =
        new UpdateAccountProfilePictureCommand(ACCOUNT_ID, new byte[] {1}, "image/png", ACTOR);

    assertThat(base)
        .isNotEqualTo(
            new UpdateAccountProfilePictureCommand(
                AccountId.newId(), new byte[] {1}, "image/png", ACTOR))
        .isNotEqualTo(
            new UpdateAccountProfilePictureCommand(ACCOUNT_ID, new byte[] {1}, "image/webp", ACTOR))
        .isNotEqualTo(
            new UpdateAccountProfilePictureCommand(
                ACCOUNT_ID,
                new byte[] {1},
                "image/png",
                AuditActor.platformAccount(java.util.UUID.randomUUID())));
  }

  @Test
  void isNotEqualToNullOrADifferentType() {
    UpdateAccountProfilePictureCommand command =
        new UpdateAccountProfilePictureCommand(ACCOUNT_ID, new byte[] {1}, "image/png", ACTOR);

    assertThat(command).isNotEqualTo(null).isNotEqualTo("not a command").isEqualTo(command);
  }

  @Test
  void toStringPrintsTheByteLengthNotTheRawBytes() {
    UpdateAccountProfilePictureCommand command =
        new UpdateAccountProfilePictureCommand(
            ACCOUNT_ID, new byte[] {1, 2, 3}, "image/png", ACTOR);

    assertThat(command.toString())
        .contains("byte[3]")
        .contains("image/png")
        .doesNotContain("1, 2, 3");
  }
}
