package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.updateaccountmetadata.InvalidMetadataException;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataCommand;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataUseCase;
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

/** Same standalone MockMvc setup as {@link PlatformAccountSettingsControllerTest}. */
class PlatformAccountMetadataControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();
  private static final AuditActor ACTOR = AuditActor.platformAccount(OWNER_ID.value());

  private GetAccountForOrganizationUseCase getAccount;
  private UpdateAccountMetadataUseCase updateMetadata;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    updateMetadata = mock(UpdateAccountMetadataUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    account = Account.register(new OrganizationId(organizationId), new Email("ada@example.com"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAccountMetadataController(
                    getAccount, updateMetadata, organizationResolver, currentPlatformAccount))
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/users/"
        + account.id().value()
        + "/metadata";
  }

  private String profileUrl() {
    return "/platform/dashboard/organizations/" + organizationId + "/users/" + account.id().value();
  }

  @Test
  void submitsAllThreeTiersAndRedirectsWithASuccessParam() throws Exception {
    mockMvc
        .perform(
            post(path())
                .param("publicMetadata", "{\"a\":1}")
                .param("privateMetadata", "{\"b\":2}")
                .param("unsafeMetadata", "{\"c\":3}"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl() + "?metadataUpdated"));

    verify(updateMetadata)
        .handle(
            new UpdateAccountMetadataCommand(
                account.id(), "{\"a\":1}", "{\"b\":2}", "{\"c\":3}", ACTOR));
  }

  @Test
  void redirectsWithAnErrorParamNamingTheInvalidTier() throws Exception {
    doThrow(new InvalidMetadataException("publicMetadata", "is not valid JSON"))
        .when(updateMetadata)
        .handle(any());

    mockMvc
        .perform(post(path()).param("publicMetadata", "not json"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl() + "?metadataError=publicMetadata"));
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
