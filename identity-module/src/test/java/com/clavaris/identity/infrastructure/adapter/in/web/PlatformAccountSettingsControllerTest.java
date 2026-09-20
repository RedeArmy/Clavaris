package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.updateaccountpermissions.UpdateAccountPermissionsCommand;
import com.clavaris.identity.application.usecase.updateaccountpermissions.UpdateAccountPermissionsUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Same standalone MockMvc setup as {@link PlatformAccountLifecycleControllerTest}. */
class PlatformAccountSettingsControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();
  private static final AuditActor ACTOR = AuditActor.platformAccount(OWNER_ID.value());

  private GetAccountForOrganizationUseCase getAccount;
  private UpdateAccountPermissionsUseCase updatePermissions;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    updatePermissions = mock(UpdateAccountPermissionsUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    account = Account.register(new OrganizationId(organizationId), new Email("ada@example.com"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAccountSettingsController(
                    getAccount, updatePermissions, organizationResolver, currentPlatformAccount))
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/users/"
        + account.id().value()
        + "/settings";
  }

  private String profileUrl() {
    return "/platform/dashboard/organizations/" + organizationId + "/users/" + account.id().value();
  }

  @Test
  void submitsBothPermissionsWhenBothCheckboxesAreChecked() throws Exception {
    mockMvc
        .perform(
            post(path()).param("canDeleteOwnAccount", "true").param("bypassesDeviceTrust", "true"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl() + "?settingsUpdated"));

    verify(updatePermissions)
        .handle(new UpdateAccountPermissionsCommand(account.id(), true, true, ACTOR));
  }

  @Test
  void defaultsBothPermissionsToFalseWhenNeitherCheckboxIsSubmitted() throws Exception {
    mockMvc
        .perform(post(path()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl() + "?settingsUpdated"));

    verify(updatePermissions)
        .handle(new UpdateAccountPermissionsCommand(account.id(), false, false, ACTOR));
  }

  @Test
  void returnsNotFoundWhenTheAccountIsUnknownOrBelongsToAnotherOrganization() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(post(path())).andExpect(status().isNotFound());
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(post(path())).andExpect(status().isNotFound());
  }
}
