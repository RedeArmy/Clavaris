package com.clavaris.identity.infrastructure.config;

import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.infrastructure.adapter.in.web.ConsumerBrandNameInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link ConsumerBrandNameInterceptor} for the consumer zone ({@code /o/**}), so every
 * page the consuming application's own people see knows the name it is branded with. Clavaris's own
 * pages live under {@code /platform/**} and never reach it.
 */
@Configuration(proxyBeanMethods = false)
class ConsumerBrandNameConfig implements WebMvcConfigurer {

  private final ClientBrandingProvider brandingProvider;

  /* package */ ConsumerBrandNameConfig(final ClientBrandingProvider brandingProvider) {
    this.brandingProvider = brandingProvider;
  }

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry
        .addInterceptor(new ConsumerBrandNameInterceptor(brandingProvider))
        .addPathPatterns("/o/**");
  }
}
