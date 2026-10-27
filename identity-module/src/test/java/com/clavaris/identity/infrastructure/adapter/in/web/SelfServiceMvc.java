package com.clavaris.identity.infrastructure.adapter.in.web;

import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * The standalone MockMvc + real Thymeleaf setup the self-service page tests share (the same one
 * {@link AccountSessionsControllerTest} builds inline): the real templates render, so a test sees
 * the page a person would, without the rest of the application around it.
 */
final class SelfServiceMvc {

  private SelfServiceMvc() {
    // Utility class — never instantiated.
  }

  static MockMvc standalone(final Object controller) {
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

    return MockMvcBuilders.standaloneSetup(controller).setViewResolvers(viewResolver).build();
  }
}
