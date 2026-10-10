package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URISyntaxException;
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

  /**
   * The request attribute naming the one origin (an {@code https://host[:port]}) the response may
   * load images from, so the application's logo can be drawn. Read by the content security policy
   * writer, which validates it before use.
   */
  public static final String LOGO_ORIGIN = "com.clavaris.identity.brandingLogoOrigin";

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
    if (isConsumerView(modelAndView)) {
      allowBrandingLogo(request, modelAndView);
      if (!modelAndView.getModel().containsKey(BRAND_NAME)) {
        organizationOf(request)
            .flatMap(
                organization ->
                    brandingProvider
                        .brandingFor(organization, request.getParameter(CLIENT_PARAM))
                        .applicationDisplayName())
            .ifPresent(name -> modelAndView.addObject(BRAND_NAME, name));
      }
    }
  }

  // The application's logo is an https URL on some other origin, and the page's content security
  // policy only allows images from this one, so the browser would refuse to draw it and show a
  // broken image. Say which origin this response may load images from: just that one, and only for
  // this response; the policy writer validates it again before using it.
  private static void allowBrandingLogo(
      final HttpServletRequest request, final ModelAndView modelAndView) {
    if (modelAndView.getModel().get("branding") instanceof ClientBrandingSnapshot snapshot) {
      snapshot
          .logoUrl()
          .flatMap(ConsumerBrandNameInterceptor::httpsOriginOf)
          .ifPresent(origin -> request.setAttribute(LOGO_ORIGIN, origin));
    }
  }

  // "https://cdn.acme.test:8443/img/logo.png?v=2" -> "https://cdn.acme.test:8443"; anything that is
  // not an https URL with a host is no origin at all.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static Optional<String> httpsOriginOf(final String url) {
    try {
      final URI uri = new URI(url);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
        return Optional.empty();
      }
      return Optional.of(
          "https://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort()));
    } catch (final URISyntaxException _) {
      return Optional.empty();
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
