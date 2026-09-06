package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.application.usecase.requestclientdomainconfig.ClientDomainConfigRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * ADR-0009 §2/§4: registers {@link CustomDomainRequestRewriteFilter} as a plain servlet filter, not
 * a {@code @Component} — Spring Boot would otherwise auto-register a bare {@code Filter} bean at
 * {@code Ordered.LOWEST_PRECEDENCE} (dead last), while this filter must run <em>before</em> Spring
 * Security's own {@code DelegatingFilterProxy} (registered at {@code
 * SecurityFilterProperties.DEFAULT_FILTER_ORDER}, {@code -100}) so it can rewrite the request path
 * before any {@code SecurityFilterChain}'s own {@code securityMatcher} ever evaluates it. Verified
 * against the real Spring Boot 4.1 source: {@code OrderedFilter.REQUEST_WRAPPER_FILTER_MAX_ORDER}
 * is {@code 0}, so Spring Security's own filter sits at {@code -100} — {@code
 * Ordered.HIGHEST_PRECEDENCE} (the JVM's own {@code Integer.MIN_VALUE}) is comfortably earlier than
 * that with no risk of a future Spring Boot patch nudging the two back into the wrong order.
 */
@Configuration
class FilterOrderingConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ FilterOrderingConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  @Bean
  /* package */ FilterRegistrationBean<CustomDomainRequestRewriteFilter>
      customDomainRequestRewriteFilter(
          final ClientDomainConfigRepository domainConfigs,
          final OAuthClientRepository oauthClients) {
    final FilterRegistrationBean<CustomDomainRequestRewriteFilter> registration =
        new FilterRegistrationBean<>(
            new CustomDomainRequestRewriteFilter(domainConfigs, oauthClients));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    registration.addUrlPatterns("/*");
    return registration;
  }
}
