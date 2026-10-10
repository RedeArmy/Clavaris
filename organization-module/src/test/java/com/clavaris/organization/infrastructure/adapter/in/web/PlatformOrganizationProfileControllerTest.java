package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesUseCase;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedUseCase;
import com.clavaris.organization.application.usecase.updateorganizationprofile.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.updateorganizationprofile.UpdateOrganizationProfileCommand;
import com.clavaris.organization.application.usecase.updateorganizationprofile.UpdateOrganizationProfileUseCase;
import com.clavaris.organization.domain.model.Organization;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * The edit dialog's save: real Thymeleaf rendering for the "rejected, dialog reopens with what was
 * typed" path, a mocked use case for what a save asks of the domain.
 */
class PlatformOrganizationProfileControllerTest {

  private static final UUID OWNER = UUID.randomUUID();
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};

  private final Organization organization = Organization.register("Acme Co", OWNER);
  private final String url = "/platform/dashboard/organizations/" + organization.id() + "/profile";

  private UpdateOrganizationProfileUseCase updateProfile;
  private ListOrganizationsForPlatformAccountPagedUseCase listOrganizations;
  private GetOrganizationProfilesUseCase getProfiles;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    updateProfile = mock(UpdateOrganizationProfileUseCase.class);
    listOrganizations = mock(ListOrganizationsForPlatformAccountPagedUseCase.class);
    getProfiles = mock(GetOrganizationProfilesUseCase.class);
    CurrentPlatformAccountResolver currentAccount = mock(CurrentPlatformAccountResolver.class);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(OWNER));
    KeysetCursor cursor = new KeysetCursor(organization.createdAt(), organization.id());
    when(listOrganizations.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(organization), cursor, cursor, false, false));
    when(getProfiles.handle(any())).thenReturn(Map.of());

    GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();
    SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");
    SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);
    ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);
    viewResolver.setCharacterEncoding("UTF-8");

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformOrganizationProfileController(
                    updateProfile,
                    new OrganizationDashboardModel(listOrganizations, getProfiles),
                    currentAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private MockMultipartHttpServletRequestBuilder valid() {
    return multipart(url)
        .param("name", "  Acme Careers  ")
        .param("description", "Hiring tools")
        .param("applicationName", "Acme Jobs")
        .param("brandColor", "#2563EB");
  }

  private UpdateOrganizationProfileCommand savedCommand() {
    ArgumentCaptor<UpdateOrganizationProfileCommand> captor =
        ArgumentCaptor.forClass(UpdateOrganizationProfileCommand.class);
    verify(updateProfile).handle(captor.capture());
    return captor.getValue();
  }

  @Test
  void aValidSaveAsksTheUseCaseAndReturnsToTheDashboard() throws Exception {
    mockMvc
        .perform(valid())
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard"));

    final UpdateOrganizationProfileCommand command = savedCommand();
    assertThat(command.organizationId()).isEqualTo(organization.id());
    assertThat(command.ownerPlatformAccountId()).isEqualTo(OWNER);
    assertThat(command.name()).isEqualTo("  Acme Careers  ");
    assertThat(command.description()).isEqualTo("Hiring tools");
    assertThat(command.applicationName()).isEqualTo("Acme Jobs");
    assertThat(command.brandColor()).isEqualTo("#2563EB");
    assertThat(command.newLogo()).isNull();
    assertThat(command.removeLogo()).isFalse();
  }

  // Saving from the second page returns to the second page, not to the first.
  @Test
  void savingReturnsToThePageThePersonWasOn() throws Exception {
    KeysetCursor cursor = new KeysetCursor(Instant.now(), UUID.randomUUID());

    mockMvc
        .perform(valid().param("after", cursor.encode()))
        .andExpect(
            redirectedUrl("/platform/dashboard?after=" + cursor.encode().replace("=", "%3D")));
    mockMvc
        .perform(valid().param("before", cursor.encode()))
        .andExpect(
            redirectedUrl("/platform/dashboard?before=" + cursor.encode().replace("=", "%3D")));
  }

  @Test
  void aValidLogoIsHandedOnAsAnImage() throws Exception {
    mockMvc
        .perform(valid().file(new MockMultipartFile("logo", "logo.png", "image/png", PNG)))
        .andExpect(redirectedUrl("/platform/dashboard"));

    final UpdateOrganizationProfileCommand command = savedCommand();
    assertThat(command.newLogo()).isNotNull();
    assertThat(command.newLogo().contentType()).isEqualTo("image/png");
    assertThat(command.newLogo().organizationId()).isEqualTo(organization.id());
  }

  @Test
  void anEmptyFileChooserMeansNoChangeToTheLogo() throws Exception {
    mockMvc
        .perform(
            valid()
                .file(new MockMultipartFile("logo", "", "application/octet-stream", new byte[0])))
        .andExpect(redirectedUrl("/platform/dashboard"));

    assertThat(savedCommand().newLogo()).isNull();
  }

  @Test
  void theRemoveSwitchIsHandedOn() throws Exception {
    mockMvc.perform(valid().param("removeLogo", "true")).andExpect(status().is3xxRedirection());

    assertThat(savedCommand().removeLogo()).isTrue();
  }

  // A rejected save re-renders the page with this Organization's dialog open, the message under the
  // field, and what the person had typed still in it.
  @Test
  void aLogoThatIsNotAnImageReopensTheDialogWithTheMessageAndTheTypedValues() throws Exception {
    mockMvc
        .perform(
            valid()
                .file(
                    new MockMultipartFile(
                        "logo", "logo.png", "image/png", "<script>alert(1)</script>".getBytes())))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard"))
        .andExpect(model().attribute("editingOrganizationId", organization.id()))
        .andExpect(content().string(containsString("data-dialog-open-on-load")))
        .andExpect(content().string(containsString("The file is not a valid image/png image")))
        .andExpect(content().string(containsString("value=\"Acme Jobs\"")))
        .andExpect(content().string(containsString(">Hiring tools</textarea>")))
        .andExpect(content().string(containsString("value=\"#2563EB\"")));

    verify(updateProfile, never()).handle(any());
  }

  @Test
  void anSvgIsRefusedAsALogoWithAPlainMessage() throws Exception {
    mockMvc
        .perform(
            valid()
                .file(
                    new MockMultipartFile(
                        "logo", "logo.svg", "image/svg+xml", "<svg/>".getBytes())))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("The logo must be a PNG, JPEG, WebP or GIF image")));

    verify(updateProfile, never()).handle(any());
  }

  @Test
  void aBlankNameReopensTheDialogAndNothingIsSaved() throws Exception {
    mockMvc
        .perform(multipart(url).param("name", "   ").param("description", "kept"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Name is required")))
        .andExpect(content().string(containsString(">kept</textarea>")))
        .andExpect(content().string(containsString("data-dialog-open-on-load")));

    verify(updateProfile, never()).handle(any());
  }

  @Test
  void theTextLimitsAreExplainedNextToTheirFields() throws Exception {
    mockMvc
        .perform(
            multipart(url)
                .param("name", "Acme")
                .param("description", "d".repeat(501))
                .param("applicationName", "a".repeat(101)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Description must be at most 500 characters")))
        .andExpect(
            content().string(containsString("Application name must be at most 100 characters")));

    verify(updateProfile, never()).handle(any());
  }

  @Test
  void aMalformedColourIsExplainedAndNothingIsSaved() throws Exception {
    mockMvc
        .perform(multipart(url).param("name", "Acme").param("brandColor", "blue"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Use a hex colour such as #2563eb")));

    verify(updateProfile, never()).handle(any());
  }

  // What the typed values come back as is escaped like everything else on the page.
  @Test
  void theTypedValuesComeBackEscaped() throws Exception {
    mockMvc
        .perform(
            multipart(url)
                .param("name", "")
                .param("description", "</textarea><script>alert(1)</script>"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
  }

  // An Organization that is not the signed-in account's is a 404, never a hint that it exists.
  @Test
  void anOrganizationThatIsNotTheCallersIsNotFound() throws Exception {
    when(updateProfile.handle(any()))
        .thenThrow(new OrganizationNotFoundException(organization.id()));

    mockMvc.perform(valid()).andExpect(status().isNotFound());
  }

  // Anything the form's own limits missed comes back from the domain and is shown on the name.
  @Test
  void aValueTheDomainRefusesReopensTheDialog() throws Exception {
    when(updateProfile.handle(any()))
        .thenThrow(new IllegalArgumentException("Organization name must not be blank"));

    mockMvc
        .perform(valid())
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Organization name must not be blank")))
        .andExpect(content().string(containsString("data-dialog-open-on-load")));
  }

  // The cursors are this page's own hidden fields; a tampered one only means "first page".
  @Test
  void aTamperedCursorDoesNotBreakAPageThatIsBeingReRendered() throws Exception {
    mockMvc
        .perform(multipart(url).param("name", "").param("after", "not-a-real-cursor"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard"));
  }
}
