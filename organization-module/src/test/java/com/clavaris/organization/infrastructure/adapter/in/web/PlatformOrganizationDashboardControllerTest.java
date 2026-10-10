package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationResult;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationprofiles.GetOrganizationProfilesUseCase;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedQuery;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccountpaged.ListOrganizationsForPlatformAccountPagedUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/**
 * Same standalone MockMvc + real Thymeleaf setup as identity-module's own {@code
 * LoginControllerTest}/{@code ConsentControllerTest} — real template rendering, not a mocked view
 * resolver, so a broken {@code th:*} expression or fragment reference fails this test, not just a
 * live server.
 */
class PlatformOrganizationDashboardControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private CreateOrganizationUseCase createOrganization;
  private ListOrganizationsForPlatformAccountPagedUseCase listOrganizations;
  private GetOrganizationProfilesUseCase getProfiles;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    createOrganization = mock(CreateOrganizationUseCase.class);
    listOrganizations = mock(ListOrganizationsForPlatformAccountPagedUseCase.class);
    getProfiles = mock(GetOrganizationProfilesUseCase.class);
    when(getProfiles.handle(any())).thenReturn(Map.of());
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(listOrganizations.handle(any())).thenReturn(emptyPage());

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
                new PlatformOrganizationDashboardController(
                    createOrganization,
                    new OrganizationDashboardModel(listOrganizations, getProfiles),
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private static KeysetPage<Organization> emptyPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  private static KeysetCursor cursorOf(final Organization organization) {
    return new KeysetCursor(organization.createdAt(), organization.id());
  }

  // Live bug, 2026-09-22: the shared sidebar's own "Manage account" trigger needs htmx.min.js
  // and organization-dialog.js unconditionally, but every page used to opt into loading them
  // independently based only on its own content's needs — sibling pages missing one or both
  // (account-profile.html, account-sessions.html, account-audit-log.html,
  // organization-danger-zone.html) silently broke "Manage account" there. Both scripts now load
  // from inside dashboard-nav.html itself (organization-module's own copy) — asserting they're
  // present here locks that fix in, not just documents it.
  @Test
  void sidebarLoadsBothScriptsManageAccountNeeds() throws Exception {
    mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/js/htmx.min.js")))
        .andExpect(content().string(containsString("/js/organization-dialog.js")))
        .andExpect(content().string(containsString("/js/htmx-feedback.js")));
  }

  @Test
  void getRendersTheEmptyStateWhenTheAccountOwnsNoOrganizations() throws Exception {
    mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard"))
        .andExpect(model().attribute("organizations", List.of()));
  }

  @Test
  void getListsEveryOrganizationTheAccountOwns() throws Exception {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    KeysetCursor cursor = cursorOf(organization);
    when(listOrganizations.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(organization), cursor, cursor, false, false));

    mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("organizations", List.of(organization)));
  }

  // TD-PERF-020 (keyset revision): proves ?after= is actually decoded and threaded into the
  // query, not silently ignored — a real, live-rendered proof the pagination wiring works end to
  // end, not just that the use case interface accepts a KeysetPageRequest.
  @Test
  void getPassesTheAfterCursorThroughToTheUseCase() throws Exception {
    KeysetCursor cursor = new KeysetCursor(java.time.Instant.now(), UUID.randomUUID());

    mockMvc
        .perform(get("/platform/dashboard").param("after", cursor.encode()))
        .andExpect(status().isOk());

    verify(listOrganizations)
        .handle(
            new ListOrganizationsForPlatformAccountPagedQuery(
                OWNER_ID, KeysetPageRequest.after(cursor)));
  }

  // Real Thymeleaf rendering proof that the Previous/Next nav only appears once there's actually
  // a further page to go to — not just that the controller resolves without throwing.
  @Test
  void rendersPaginationControlsOnlyWhenAFurtherPageExists() throws Exception {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    KeysetCursor cursor = cursorOf(organization);
    when(listOrganizations.handle(
            new ListOrganizationsForPlatformAccountPagedQuery(OWNER_ID, KeysetPageRequest.first())))
        .thenReturn(new KeysetPage<>(List.of(organization), cursor, cursor, true, false));

    mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("clavaris-pagination")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Next")));
  }

  @Test
  void rendersNoPaginationControlsWhenThereIsOnlyOnePage() throws Exception {
    mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("clavaris-pagination"))));
  }

  @Test
  void plainFormPostRedirectsAfterCreatingAnOrganization() throws Exception {
    CreateOrganizationResult result = mock(CreateOrganizationResult.class);
    when(createOrganization.handle(any())).thenReturn(result);

    mockMvc
        .perform(post("/platform/dashboard").param("name", "New Co"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard"));

    verify(createOrganization).handle(any());
  }

  @Test
  void plainFormPostWithNoNameReRendersTheFullPageWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(post("/platform/dashboard").param("name", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard"));

    verify(createOrganization, never()).handle(any());
  }

  // ADR-0025: an HTMX-originated POST (HX-Request: true) must get back just the content fragment,
  // not a redirect — the whole point of hx-target/hx-swap on the page's own form.
  @Test
  void htmxPostReturnsTheContentFragmentInsteadOfARedirect() throws Exception {
    CreateOrganizationResult result = mock(CreateOrganizationResult.class);
    when(createOrganization.handle(any())).thenReturn(result);

    mockMvc
        .perform(post("/platform/dashboard").param("name", "New Co").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard :: content"));

    verify(createOrganization).handle(any());
  }

  @Test
  void htmxPostWithNoNameReturnsTheContentFragmentNotAFullPage() throws Exception {
    mockMvc
        .perform(post("/platform/dashboard").param("name", "").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard :: content"));

    verify(createOrganization, never()).handle(any());
  }

  // --- the Organization card and its edit dialog ---------------------------------------------

  private Organization showOneOrganization() {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    KeysetCursor cursor = cursorOf(organization);
    when(listOrganizations.handle(any()))
        .thenReturn(new KeysetPage<>(List.of(organization), cursor, cursor, false, false));
    return organization;
  }

  private static String card(final String page) {
    final int start = page.indexOf("<article class=\"clavaris-organization-card\"");
    return page.substring(start, page.indexOf("</article>", start));
  }

  private String page() throws Exception {
    return mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // The edit icon sits where the environment badge used to; the badge sits where "Created ..." used
  // to, beside "Open"; and the creation date is gone.
  @Test
  void theCardHasTheEditIconInItsHeaderAndTheEnvironmentInItsFooterAndNoCreationDate()
      throws Exception {
    Organization organization = showOneOrganization();

    final String card = card(page());

    final int icon = card.indexOf("clavaris-icon-button");
    final int name = card.indexOf("<h2");
    final int badge = card.indexOf("clavaris-badge");
    final int open = card.indexOf(">Open");
    org.assertj.core.api.Assertions.assertThat(icon).isPositive().isLessThan(name);
    org.assertj.core.api.Assertions.assertThat(badge).isGreaterThan(name).isLessThan(open);
    org.assertj.core.api.Assertions.assertThat(card)
        .contains("aria-label=\"Edit organization\"")
        .contains("data-dialog-open=\"edit-organization-" + organization.id() + "\"")
        .contains("DEVELOPMENT")
        .doesNotContain("Created")
        .doesNotContain("<time");
  }

  // With no description there is no text at all: never the old default sentence.
  @Test
  void anOrganizationWithNoDescriptionShowsNoDescriptionText() throws Exception {
    showOneOrganization();

    mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(content().string(not(containsString("Isolated tenant with its own accounts"))));
    org.assertj.core.api.Assertions.assertThat(card(page())).doesNotContain("<p>");
  }

  @Test
  void theDescriptionIsShownInPlaceOfTheDefaultTextAndEscaped() throws Exception {
    Organization organization = showOneOrganization();
    when(getProfiles.handle(any()))
        .thenReturn(
            Map.of(
                organization.id(),
                OrganizationProfile.empty(organization.id())
                    .withDetails("Hiring <b>tools</b> for recruiters", null, null)));

    final String card = card(page());

    org.assertj.core.api.Assertions.assertThat(card)
        .contains("Hiring &lt;b&gt;tools&lt;/b&gt; for recruiters")
        .doesNotContain("<b>tools</b>")
        .doesNotContain("Isolated tenant");
  }

  @Test
  void theEditDialogIsFilledFromTheOrganizationAndItsProfile() throws Exception {
    Organization organization = showOneOrganization();
    Instant logoAt = Instant.parse("2026-10-10T12:00:00Z");
    when(getProfiles.handle(any()))
        .thenReturn(
            Map.of(
                organization.id(),
                OrganizationProfile.empty(organization.id())
                    .withDetails("A description", "Acme Jobs", "#2563EB")
                    .withLogoUpdatedAt(logoAt)));

    final String page = page();

    org.assertj.core.api.Assertions.assertThat(page)
        .contains("id=\"edit-organization-" + organization.id() + "\"")
        .contains("action=\"/platform/dashboard/organizations/" + organization.id() + "/profile\"")
        .contains("enctype=\"multipart/form-data\"")
        .contains("value=\"Acme Co\"")
        .contains("value=\"Acme Jobs\"")
        .contains("value=\"#2563eb\"")
        .contains(">A description</textarea>")
        .contains("/o/" + organization.id() + "/branding/logo?v=" + logoAt.toEpochMilli())
        .contains("name=\"removeLogo\"");
  }

  @Test
  void anOrganizationWithNoLogoShowsAPlaceholderAndNoRemoveSwitch() throws Exception {
    showOneOrganization();

    final String page = page();

    org.assertj.core.api.Assertions.assertThat(page)
        .contains("data-logo-placeholder")
        .doesNotContain("data-logo-remove")
        .doesNotContain("/branding/logo");
  }

  // The page's own cursors ride in each dialog so a save returns to the page the person was on.
  @Test
  void theEditDialogCarriesThePagesCursors() throws Exception {
    showOneOrganization();
    KeysetCursor cursor = new KeysetCursor(Instant.now(), UUID.randomUUID());

    mockMvc
        .perform(get("/platform/dashboard").param("after", cursor.encode()))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("name=\"after\" value=\"" + cursor.encode() + "\"")));
  }
}
