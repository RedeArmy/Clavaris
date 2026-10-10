package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * The standard for the consumer system: a page shows the application's own name, and its
 * Organization's when it has none. This is what hands that name to the page, and it must do so for
 * the consumer's views only.
 */
class ConsumerBrandNameInterceptorTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private final ClientBrandingProvider branding = mock(ClientBrandingProvider.class);
  private final ConsumerBrandNameInterceptor interceptor =
      new ConsumerBrandNameInterceptor(branding);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  private static ClientBrandingSnapshot named(final String name) {
    return new ClientBrandingSnapshot(Optional.empty(), Optional.empty(), Optional.of(name));
  }

  private void inOrganization() {
    request.setAttribute(
        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
        Map.of("organizationId", ORGANIZATION.toString()));
  }

  private ModelAndView run(final ModelAndView view) {
    interceptor.postHandle(request, response, new Object(), view);
    return view;
  }

  @Test
  void aConsumerPageGetsTheBrandName() {
    inOrganization();
    when(branding.brandingFor(new OrganizationId(ORGANIZATION), null))
        .thenReturn(named("Acme Analytics"));

    final ModelAndView view = run(new ModelAndView("identity/forgot-password"));

    assertThat(view.getModel()).containsEntry("brandName", "Acme Analytics");
  }

  @Test
  void theClientTheRequestNamesIsWhatTheBrandIsResolvedFor() {
    inOrganization();
    request.setParameter("clientId", "acme-web");
    when(branding.brandingFor(new OrganizationId(ORGANIZATION), "acme-web"))
        .thenReturn(named("Acme Dashboard"));

    final ModelAndView view = run(new ModelAndView("identity/register"));

    assertThat(view.getModel()).containsEntry("brandName", "Acme Dashboard");
  }

  // Clavaris's own pages are never given a brand name.
  @Test
  void aPlatformViewIsLeftAlone() {
    inOrganization();

    final ModelAndView view = run(new ModelAndView("identity/platform/login"));

    assertThat(view.getModel()).doesNotContainKey("brandName");
    verify(branding, never()).brandingFor(any(), any());
  }

  @Test
  void aViewOutsideTheIdentityPagesIsLeftAlone() {
    inOrganization();

    final ModelAndView view = run(new ModelAndView("organization/platform/organization-detail"));

    assertThat(view.getModel()).doesNotContainKey("brandName");
    verify(branding, never()).brandingFor(any(), any());
  }

  @Test
  void aRedirectIsLeftAlone() {
    inOrganization();

    final ModelAndView view = run(new ModelAndView("redirect:/o/" + ORGANIZATION + "/login"));

    assertThat(view.getModel()).doesNotContainKey("brandName");
    verify(branding, never()).brandingFor(any(), any());
  }

  @Test
  void aPageThatAlreadyKnowsItsBrandNameKeepsIt() {
    inOrganization();
    final ModelAndView page = new ModelAndView("identity/login");
    page.addObject("brandName", "Already Set");

    run(page);

    assertThat(page.getModel()).containsEntry("brandName", "Already Set");
    verify(branding, never()).brandingFor(any(), any());
  }

  @Test
  void aRequestWithNoOrganizationInItsPathIsLeftAlone() {
    final ModelAndView view = run(new ModelAndView("identity/consent-error"));

    assertThat(view.getModel()).doesNotContainKey("brandName");
    verify(branding, never()).brandingFor(any(), any());
  }

  @Test
  void anOrganizationIdThatIsNotAnIdIsLeftAlone() {
    request.setAttribute(
        HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("organizationId", "not-a-uuid"));

    final ModelAndView view = run(new ModelAndView("identity/login"));

    assertThat(view.getModel()).doesNotContainKey("brandName");
    verify(branding, never()).brandingFor(any(), any());
  }

  // Even the Organization's name could not be found: the page names nobody, not Clavaris.
  @Test
  void withNoNameAtAllNothingIsAdded() {
    inOrganization();
    when(branding.brandingFor(any(), any())).thenReturn(ClientBrandingSnapshot.unconfigured());

    final ModelAndView view = run(new ModelAndView("identity/forgot-password"));

    assertThat(view.getModel()).doesNotContainKey("brandName");
  }

  @Test
  void aRequestWithNoViewIsLeftAlone() {
    inOrganization();

    interceptor.postHandle(request, response, new Object(), null);

    verify(branding, never()).brandingFor(any(), any());
  }
}
