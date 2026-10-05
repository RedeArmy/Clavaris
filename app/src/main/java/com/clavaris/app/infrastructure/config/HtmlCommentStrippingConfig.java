package com.clavaris.app.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link HtmlCommentStrippingDialect} with the application's Thymeleaf engine (Spring
 * Boot adds every {@code IDialect} bean to it), so no template's HTML comments reach a browser.
 */
@Configuration(proxyBeanMethods = false)
public class HtmlCommentStrippingConfig {

  // PMD.UnnecessaryConstructor: AtLeastOneConstructor asks for it and the two rules contradict each
  // other; same resolution the other classes without state in this codebase use.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public HtmlCommentStrippingConfig() {
    // Spring instantiates this; the only content is the @Bean method below.
  }

  @Bean
  public HtmlCommentStrippingDialect htmlCommentStrippingDialect() {
    return new HtmlCommentStrippingDialect();
  }
}
