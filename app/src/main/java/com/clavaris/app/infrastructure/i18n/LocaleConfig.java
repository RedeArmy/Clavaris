package com.clavaris.app.infrastructure.i18n;

import com.clavaris.common.i18n.AppLocales;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

/**
 * Chooses the language each request is served in, and lets a person change it.
 *
 * <p>Order of precedence: the {@code ?lang=xx} on the request (which also sets the cookie), then
 * the {@code clavaris-lang} cookie, then the browser's {@code Accept-Language} (the first language
 * it lists that the product supports), then English. Only {@link AppLocales#SUPPORTED} languages
 * are ever honoured: an unknown {@code lang} is ignored rather than rejected, so a stale link never
 * breaks a page.
 *
 * <p>The cookie is functional, not tracking: it stores one of two language codes, is {@code
 * SameSite=Lax}, and lives a year.
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig implements WebMvcConfigurer {

  /* default */ static final String COOKIE_NAME = "clavaris-lang";

  private final ObjectProvider<LocaleResolver> resolvers;

  public LocaleConfig(final ObjectProvider<LocaleResolver> resolvers) {
    this.resolvers = resolvers;
  }

  /** Must be called {@code localeResolver}: that is the bean name Spring MVC looks for. */
  @Bean
  public LocaleResolver localeResolver() {
    final CookieLocaleResolver resolver = new CookieLocaleResolver(COOKIE_NAME);
    resolver.setDefaultLocaleFunction(LocaleConfig::preferredLocale);
    resolver.setCookieMaxAge(Duration.ofDays(365));
    resolver.setCookiePath("/");
    resolver.setCookieSameSite("Lax");
    resolver.setCookieHttpOnly(true);
    return resolver;
  }

  @Bean
  public LocalizationDialect localizationDialect() {
    return new LocalizationDialect();
  }

  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry.addInterceptor(new LanguageInterceptor(resolvers.getObject()));
  }

  /**
   * The first language in the browser's preference list that the product supports, else English.
   */
  /* default */ static Locale preferredLocale(final HttpServletRequest request) {
    return Collections.list(request.getLocales()).stream()
        .map(locale -> AppLocales.parse(locale.getLanguage()))
        .flatMap(Optional::stream)
        .findFirst()
        .orElse(AppLocales.ENGLISH);
  }

  /** Applies {@code ?lang=xx} and states the response language. */
  /* default */ static final class LanguageInterceptor implements HandlerInterceptor {

    private final LocaleResolver resolver;

    /* default */ LanguageInterceptor(final LocaleResolver resolver) {
      this.resolver = resolver;
    }

    @Override
    public boolean preHandle(
        final HttpServletRequest request,
        final HttpServletResponse response,
        final Object handler) {
      AppLocales.parse(request.getParameter(LanguageSwitcher.PARAMETER))
          .ifPresent(locale -> resolver.setLocale(request, response, locale));
      response.setHeader(
          "Content-Language", AppLocales.resolve(resolver.resolveLocale(request)).getLanguage());
      return true;
    }
  }
}
