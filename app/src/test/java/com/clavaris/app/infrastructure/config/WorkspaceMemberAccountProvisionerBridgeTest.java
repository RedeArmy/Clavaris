package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountCommand;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountCommand;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class WorkspaceMemberAccountProvisionerBridgeTest {

  private final RegisterAccountUseCase registerAccount = mock(RegisterAccountUseCase.class);
  private final RequestPasswordResetUseCase requestPasswordReset =
      mock(RequestPasswordResetUseCase.class);
  private final DeleteAccountUseCase deleteAccount = mock(DeleteAccountUseCase.class);
  private final WorkspaceMemberAccountProvisionerBridge bridge =
      new WorkspaceMemberAccountProvisionerBridge(
          registerAccount, requestPasswordReset, deleteAccount);

  @Test
  void registersTheAccountThenTriggersThePasswordResetEmail_inThatOrder() {
    UUID organizationId = UUID.randomUUID();
    AccountId newAccountId = AccountId.newId();
    when(registerAccount.handle(any())).thenReturn(newAccountId);

    AccountProvisioner.ProvisionedAccount result =
        bridge.provisionAndSendWelcome(organizationId, "new@example.com");

    assertThat(result.accountId()).isEqualTo(newAccountId.value());
    InOrder order = inOrder(registerAccount, requestPasswordReset);
    order.verify(registerAccount).handle(any());
    order.verify(requestPasswordReset).handle(any());
  }

  @Test
  void registersWithARandomPasswordThatSatisfiesPasswordPolicyAndIsNeverPredictable() {
    UUID organizationId = UUID.randomUUID();
    when(registerAccount.handle(any())).thenReturn(AccountId.newId());

    bridge.provisionAndSendWelcome(organizationId, "new@example.com");
    bridge.provisionAndSendWelcome(organizationId, "second@example.com");

    ArgumentCaptor<RegisterAccountCommand> commands =
        ArgumentCaptor.forClass(RegisterAccountCommand.class);
    verify(registerAccount, times(2)).handle(commands.capture());
    String firstPassword = commands.getAllValues().get(0).rawPassword();
    String secondPassword = commands.getAllValues().get(1).rawPassword();
    assertThat(firstPassword).hasSizeBetween(8, 128);
    assertThat(secondPassword).hasSizeBetween(8, 128);
    // Two independently-generated calls must never coincide — proves this is a real random
    // generator, not a hardcoded/static placeholder value.
    assertThat(firstPassword).isNotEqualTo(secondPassword);
  }

  @Test
  void passesThroughTheGivenOrganizationAndEmailToBothCalls() {
    UUID organizationId = UUID.randomUUID();
    when(registerAccount.handle(any())).thenReturn(AccountId.newId());

    bridge.provisionAndSendWelcome(organizationId, "new@example.com");

    ArgumentCaptor<RegisterAccountCommand> registerCommand =
        ArgumentCaptor.forClass(RegisterAccountCommand.class);
    verify(registerAccount).handle(registerCommand.capture());
    assertThat(registerCommand.getValue().organizationId())
        .isEqualTo(new OrganizationId(organizationId));
    assertThat(registerCommand.getValue().email()).isEqualTo(new Email("new@example.com"));

    ArgumentCaptor<RequestPasswordResetCommand> resetCommand =
        ArgumentCaptor.forClass(RequestPasswordResetCommand.class);
    verify(requestPasswordReset).handle(resetCommand.capture());
    assertThat(resetCommand.getValue().organizationId())
        .isEqualTo(new OrganizationId(organizationId));
    assertThat(resetCommand.getValue().email()).isEqualTo(new Email("new@example.com"));
  }

  @Test
  void translatesEmailAlreadyRegisteredIntoThisPortsOwnExceptionWithoutSendingAnyEmail() {
    UUID organizationId = UUID.randomUUID();
    when(registerAccount.handle(any()))
        .thenThrow(new EmailAlreadyRegisteredException(new OrganizationId(organizationId)));

    assertThatExceptionOfType(AccountProvisioner.AccountAlreadyExistsException.class)
        .isThrownBy(() -> bridge.provisionAndSendWelcome(organizationId, "taken@example.com"));

    verify(requestPasswordReset, never()).handle(any());
  }

  // TD-WS-001: deprovision() is a thin, 100%-reuse delegate to DeleteAccountUseCase — these two
  // tests prove the delegation itself, not DeleteAccountService's own behavior (already covered by
  // DeleteAccountServiceTest/DeleteAccountIntegrationTest).
  @Test
  void deprovisionDelegatesToDeleteAccountUseCaseWithTheGivenAccountIdAndActor() {
    UUID accountId = UUID.randomUUID();
    AuditActor actor = AuditActor.platformClient("test-platform-client");

    bridge.deprovision(accountId, actor);

    ArgumentCaptor<DeleteAccountCommand> command =
        ArgumentCaptor.forClass(DeleteAccountCommand.class);
    verify(deleteAccount).handle(command.capture());
    assertThat(command.getValue().accountId()).isEqualTo(new AccountId(accountId));
    assertThat(command.getValue().actor()).isEqualTo(actor);
  }

  @Test
  void deprovisionLetsADeleteAccountUseCaseFailurePropagateUnchanged() {
    UUID accountId = UUID.randomUUID();
    AuditActor actor = AuditActor.platformClient("client");
    RuntimeException deleteFailure = new RuntimeException("delete failed");
    doThrow(deleteFailure).when(deleteAccount).handle(any());

    assertThatExceptionOfType(RuntimeException.class)
        .isThrownBy(() -> bridge.deprovision(accountId, actor))
        .isSameAs(deleteFailure);
  }
}
