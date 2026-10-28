package com.clavaris.identity.application.usecase.changepassword;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeCommand;
import com.clavaris.identity.application.usecase.connectsocialidentity.ConnectSocialIdentityCommand;
import com.clavaris.identity.application.usecase.connectsocialidentity.SocialIdentityAlreadyLinkedException;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeCommand;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.SocialProvider;
import org.junit.jupiter.api.Test;

/**
 * BR-DATA-01: a command carrying a password, a link token or an address must not put it in a log
 * line through a stray {@code toString()}.
 */
class CommandRedactionTest {

  private final AccountId accountId = AccountId.newId();

  @Test
  void changePasswordCommandNeverPrintsEitherPassword() {
    String printed =
        new ChangePasswordCommand(
                accountId,
                "current-secret",
                "new-secret-value",
                "sid",
                AuditActor.account(accountId.value()),
                true)
            .toString();

    assertThat(printed).doesNotContain("current-secret").doesNotContain("new-secret-value");
  }

  @Test
  void requestEmailChangeCommandNeverPrintsThePasswordOrTheAddress() {
    String printed =
        new RequestEmailChangeCommand(
                accountId,
                "current-secret",
                new Email("someone@example.com"),
                AuditActor.account(accountId.value()),
                true)
            .toString();

    assertThat(printed).doesNotContain("current-secret").doesNotContain("someone@example.com");
  }

  @Test
  void confirmEmailChangeCommandNeverPrintsTheToken() {
    assertThat(new ConfirmEmailChangeCommand(accountId, "raw-link-token").toString())
        .doesNotContain("raw-link-token");
  }

  @Test
  void connectSocialIdentityCommandNeverPrintsTheProviderSubject() {
    assertThat(
            new ConnectSocialIdentityCommand(accountId, SocialProvider.GITHUB, "provider-subject-9")
                .toString())
        .doesNotContain("provider-subject-9");
  }

  @Test
  void theAlreadyLinkedExceptionKeepsItsCause() {
    Throwable cause = new IllegalStateException("constraint");

    assertThat(new SocialIdentityAlreadyLinkedException(cause)).hasCause(cause);
  }
}
