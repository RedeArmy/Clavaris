package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionLimitExceededException;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryUseCase;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.DuplicateAccessRestrictionEntryException;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization.PreviewAccessRestrictionForOrganizationQuery;
import com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization.PreviewAccessRestrictionForOrganizationUseCase;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.AccessRestrictionEntryNotFoundException;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryUseCase;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.RestrictionType;
import com.clavaris.organization.domain.service.AccessRestrictionDecision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

class PlatformAccessRestrictionsControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final String VIEW = "organization/platform/organization-access-restrictions";

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private ListAccessRestrictionEntriesForOrganizationUseCase listEntries;
  private AddAccessRestrictionEntryUseCase addEntry;
  private RemoveAccessRestrictionEntryUseCase removeEntry;
  private PreviewAccessRestrictionForOrganizationUseCase previewAccess;
  private Organization organization;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    listEntries = mock(ListAccessRestrictionEntriesForOrganizationUseCase.class);
    addEntry = mock(AddAccessRestrictionEntryUseCase.class);
    removeEntry = mock(RemoveAccessRestrictionEntryUseCase.class);
    previewAccess = mock(PreviewAccessRestrictionForOrganizationUseCase.class);
    final CurrentPlatformAccountResolver currentPlatformAccount =
        mock(CurrentPlatformAccountResolver.class);
    organization = Organization.register("Acme Co", OWNER_ID);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(listEntries.handle(any())).thenReturn(List.of());

    final GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();
    final SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");
    final SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);
    final ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAccessRestrictionsController(
                    getOrganization,
                    listEntries,
                    addEntry,
                    removeEntry,
                    previewAccess,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organization.id() + "/access-restrictions";
  }

  private String render() throws Exception {
    return mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name(VIEW))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void anOrganizationWithNoEntriesSaysAnyoneMayRegister() throws Exception {
    assertThat(render()).contains("Nothing is restricted: anyone may register.");
  }

  @Test
  void theListsAreShownEachUnderItsOwnHeadingAndAnAllowlistIsExplained() throws Exception {
    when(listEntries.handle(any()))
        .thenReturn(
            List.of(
                AccessRestrictionEntry.create(
                    organization.id(), RestrictionType.ALLOWLIST, "@example.com"),
                AccessRestrictionEntry.create(
                    organization.id(), RestrictionType.BLOCKLIST, "spam@x.test")));

    final String body = render();

    assertThat(body)
        .contains("@example.com")
        .contains("spam@x.test")
        .contains("only the addresses and domains in it may register")
        .doesNotContain("Nothing is restricted");
  }

  @Test
  void anEntryIsAddedNormalisedAndTheListIsShownAgain() throws Exception {
    mockMvc
        .perform(post(path()).param("type", "BLOCKLIST").param("identifier", "  Ana@Example.COM "))
        .andExpect(redirectedUrl(path()));

    verify(addEntry)
        .handle(
            new AddAccessRestrictionEntryCommand(
                organization.id(), RestrictionType.BLOCKLIST, "ana@example.com"));
  }

  @Test
  void aDomainWithALeadingAtIsAccepted() throws Exception {
    mockMvc
        .perform(post(path()).param("type", "ALLOWLIST").param("identifier", "@example.com"))
        .andExpect(redirectedUrl(path()));

    verify(addEntry).handle(any());
  }

  @Test
  void somethingThatCouldNeverMatchIsRefusedWithoutSavingAndKeepsWhatWasTyped() throws Exception {
    for (final String typo :
        new String[] {"example.com", "ana@", "@@x.test", "ana example@x.test", ""}) {
      final String body =
          mockMvc
              .perform(post(path()).param("type", "BLOCKLIST").param("identifier", typo))
              .andExpect(status().isOk())
              .andExpect(view().name(VIEW))
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(body).as(typo).contains("Enter an email address such as ana@example.com");
    }

    verify(addEntry, never()).handle(any());
  }

  @Test
  void aDuplicateIsReportedNotThrown() throws Exception {
    doThrow(new DuplicateAccessRestrictionEntryException("ana@example.com"))
        .when(addEntry)
        .handle(any());

    final String body =
        mockMvc
            .perform(post(path()).param("type", "BLOCKLIST").param("identifier", "ana@example.com"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("already in the list");
  }

  @Test
  void anEntryIsRemovedAndRemovingOneThatIsAlreadyGoneStillLandsOnTheList() throws Exception {
    final UUID entryId = UUID.randomUUID();
    mockMvc.perform(post(path() + "/" + entryId + "/remove")).andExpect(redirectedUrl(path()));
    verify(removeEntry).handle(new RemoveAccessRestrictionEntryCommand(organization.id(), entryId));

    doThrow(new AccessRestrictionEntryNotFoundException(entryId)).when(removeEntry).handle(any());
    mockMvc.perform(post(path() + "/" + entryId + "/remove")).andExpect(redirectedUrl(path()));
  }

  @Test
  void anOrganizationThatIsNotTheCallersIsNotFoundAndNothingChanges() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
    mockMvc
        .perform(post(path()).param("type", "BLOCKLIST").param("identifier", "a@b.test"))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(post(path() + "/" + UUID.randomUUID() + "/remove"))
        .andExpect(status().isNotFound());

    verify(addEntry, never()).handle(any());
    verify(removeEntry, never()).handle(any());
  }

  // TD-FUT-069: a dry run names the rule and creates nothing.
  @Test
  void tryingAnAddressShowsTheVerdictAndTheEntryThatDecidedIt() throws Exception {
    when(previewAccess.handle(any()))
        .thenReturn(
            new AccessRestrictionDecision(
                false, AccessRestrictionDecision.Reason.BLOCKED_BY_BLOCKLIST, "@spam.test"));

    final String body =
        mockMvc
            .perform(post(path() + "/preview").param("email", "  Bob@Spam.test "))
            .andExpect(status().isOk())
            .andExpect(view().name(VIEW))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("Refused.").contains("blocklist entry").contains("@spam.test");
    verify(previewAccess)
        .handle(
            new PreviewAccessRestrictionForOrganizationQuery(organization.id(), "Bob@Spam.test"));
    verify(addEntry, never()).handle(any());
  }

  @Test
  void tryingAnAddressTheListsAllowSaysSo() throws Exception {
    when(previewAccess.handle(any()))
        .thenReturn(
            new AccessRestrictionDecision(
                true, AccessRestrictionDecision.Reason.NOT_RESTRICTED, null));

    final String body =
        mockMvc
            .perform(post(path() + "/preview").param("email", "ana@example.com"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("Allowed.").contains("anyone may register");
  }

  @Test
  void aBlankOrOverlongAddressIsRefusedWithoutAskingTheLists() throws Exception {
    for (final String bad : new String[] {"", "   ", "a".repeat(321)}) {
      final String body =
          mockMvc
              .perform(post(path() + "/preview").param("email", bad))
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).as("length " + bad.length()).contains("Enter an email address to check.");
    }

    verify(previewAccess, never()).handle(any());
  }

  // TD-SEC-087: the form refuses what the column could not hold, instead of a 500.
  @Test
  void anOverlongEntryIsRefusedByTheFormNotTheDatabase() throws Exception {
    final String tooLong = "a".repeat(320) + "@example.com";

    final String body =
        mockMvc
            .perform(post(path()).param("type", "BLOCKLIST").param("identifier", tooLong))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("Enter an email address such as ana@example.com");
    verify(addEntry, never()).handle(any());
  }

  @Test
  void anOrganizationAtItsEntryCapIsToldSoAndKeepsWhatWasTyped() throws Exception {
    doThrow(new AccessRestrictionLimitExceededException(organization.id(), 1_000))
        .when(addEntry)
        .handle(any());

    final String body =
        mockMvc
            .perform(post(path()).param("type", "BLOCKLIST").param("identifier", "ana@example.com"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("maximum number of entries").contains("ana@example.com");
  }
}
