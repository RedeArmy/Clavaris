package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.ForcePasswordResetForAccountCommand;
import com.clavaris.identity.application.usecase.forcepasswordresetforaccount.ForcePasswordResetForAccountUseCase;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureCommand;
import com.clavaris.identity.application.usecase.removeaccountprofilepicture.RemoveAccountProfilePictureUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileCommand;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileUseCase;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.InvalidProfilePictureException;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.UpdateAccountProfilePictureUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Same standalone MockMvc setup as {@link PlatformAccountLifecycleControllerTest}. */
class PlatformAccountProfileAdminControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();
  private static final AuditActor ACTOR = AuditActor.platformAccount(OWNER_ID.value());

  private GetAccountForOrganizationUseCase getAccount;
  private UpdateAccountProfileUseCase updateProfile;
  private UpdateAccountProfilePictureUseCase updatePicture;
  private RemoveAccountProfilePictureUseCase removePicture;
  private ForcePasswordResetForAccountUseCase forcePasswordReset;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;
  private Account account;

  @BeforeEach
  void setUp() {
    getAccount = mock(GetAccountForOrganizationUseCase.class);
    updateProfile = mock(UpdateAccountProfileUseCase.class);
    updatePicture = mock(UpdateAccountProfilePictureUseCase.class);
    removePicture = mock(RemoveAccountProfilePictureUseCase.class);
    forcePasswordReset = mock(ForcePasswordResetForAccountUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();
    account = Account.register(new OrganizationId(organizationId), new Email("ada@example.com"));

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(getAccount.handle(any())).thenReturn(Optional.of(account));

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAccountProfileAdminController(
                    getAccount,
                    updateProfile,
                    updatePicture,
                    removePicture,
                    forcePasswordReset,
                    organizationResolver,
                    currentPlatformAccount))
            .build();
  }

  private String path(final String action) {
    return "/platform/dashboard/organizations/"
        + organizationId
        + "/users/"
        + account.id().value()
        + "/"
        + action;
  }

  private String profileUrl() {
    return "/platform/dashboard/organizations/" + organizationId + "/users/" + account.id().value();
  }

  @Test
  void updateProfileDelegatesAndRedirects() throws Exception {
    mockMvc
        .perform(post(path("profile")).param("firstName", "Ada").param("lastName", "Lovelace"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl() + "?profileUpdated"));

    verify(updateProfile)
        .handle(new UpdateAccountProfileCommand(account.id(), "Ada", "Lovelace", ACTOR));
  }

  @Test
  void uploadPictureDelegatesAndRedirects() throws Exception {
    final MockMultipartFile file =
        new MockMultipartFile("file", "avatar.png", "image/png", new byte[] {1, 2, 3});

    mockMvc
        .perform(multipart(path("picture")).file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl() + "?pictureUpdated"));

    verify(updatePicture).handle(any());
  }

  @Test
  void uploadPictureRedirectsWithAnEncodedErrorOnInvalidUpload() throws Exception {
    doThrow(new InvalidProfilePictureException("Unsupported image type: image/svg+xml"))
        .when(updatePicture)
        .handle(any());
    final MockMultipartFile file =
        new MockMultipartFile("file", "avatar.svg", "image/svg+xml", new byte[] {1});

    mockMvc
        .perform(multipart(path("picture")).file(file))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern(profileUrl() + "?pictureError=*"));
  }

  @Test
  void removePictureDelegatesAndRedirects() throws Exception {
    mockMvc
        .perform(post(path("picture/remove")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl() + "?pictureRemoved"));

    verify(removePicture).handle(new RemoveAccountProfilePictureCommand(account.id(), ACTOR));
  }

  @Test
  void forcePasswordResetDelegatesAndRedirects() throws Exception {
    mockMvc
        .perform(post(path("force-password-reset")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(profileUrl() + "?passwordResetSent"));

    verify(forcePasswordReset).handle(new ForcePasswordResetForAccountCommand(account.id(), ACTOR));
  }

  @Test
  void returnsNotFoundWhenTheAccountIsUnknownOrBelongsToAnotherOrganization() throws Exception {
    when(getAccount.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(post(path("force-password-reset"))).andExpect(status().isNotFound());
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(post(path("force-password-reset"))).andExpect(status().isNotFound());
  }
}
