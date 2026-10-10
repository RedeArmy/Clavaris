package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * Gives every page the consuming application's own people see the name it is branded with, so the
 * browser tab can read "Acme Analytics — Check your email" and never "... — Clavaris".
 *
 * <p>The standard for the consumer system: the application's own display name when it has one, and
 * otherwise its Organization's name ({@code ClientBrandingProvider} already applies that fallback).
 * It runs only for a rendered consumer view under {@code /o/{organizationId}/...}: Clavaris's own
 * platform and dashboard views are never touched, and a view that already set {@code brandName}
 * (the sign-in and consent pages know their branding from the request itself) is left alone.
 *
 * <p>Registered by {@code ConsumerBrandNameConfig}; the page reads the value in {@code
 * fragments/consumer-head}.
 */
public class ConsumerBrandNameInterceptor implements HandlerInterceptor {

  /** The model attribute the page header reads. */
  public static final String BRAND_NAME = "brandName";

  private static final String VIEW_PREFIX = "identity/";
  private static final String PLATFORM_PREFIX = "identity/platform/";
  private static final String ORG_VARIABLE = "organizationId";
  private static final String CLIENT_PARAM = "clientId";

  private final ClientBrandingProvider brandingProvider;

  public ConsumerBrandNameInterceptor(final ClientBrandingProvider brandingProvider) {
    this.brandingProvider = brandingProvider;
  }

  @Override
  public void postHandle(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Object handler,
      final ModelAndView modelAndView) {
    if (isConsumerView(modelAndView) && !modelAndView.getModel().containsKey(BRAND_NAME)) {
      organizationOf(request)
          .flatMap(
              organization ->
                  brandingProvider
                      .brandingFor(organization, request.getParameter(CLIENT_PARAM))
                      .applicationDisplayName())
          .ifPresent(name -> modelAndView.addObject(BRAND_NAME, name));
    }
  }

  // A rendered view of the consumer zone: not a redirect, and not Clavaris's own platform tier.
  private static boolean isConsumerView(final ModelAndView modelAndView) {
    final String view = modelAndView == null ? null : modelAndView.getViewName();
    return view != null && view.startsWith(VIEW_PREFIX) && !view.startsWith(PLATFORM_PREFIX);
  }

  // An absent or malformed id is "no organization", not an error: the page just has no brand.
  // PMD.OnlyOneReturn: the try block and its catch each have their own exit, same rationale as
  // every
  // other parse-or-nothing helper in this package.
  @SuppressWarnings({"unchecked", "PMD.OnlyOneReturn"})
  private static Optional<OrganizationId> organizationOf(final HttpServletRequest request) {
    final Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    final String raw =
        variables instanceof Map ? ((Map<String, String>) variables).get(ORG_VARIABLE) : null;
    try {
      return raw == null ? Optional.empty() : Optional.of(new OrganizationId(UUID.fromString(raw)));
    } catch (final IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
