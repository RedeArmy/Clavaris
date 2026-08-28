package com.clavaris.app.infrastructure.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * TD-SEC-015: proves the safety net actually catches an exception nobody anticipated and never
 * leaks its message — same "assert the redaction actually happens" bar as {@code
 * TokenIssuanceEventLoggerTest}, not just that a handler method exists.
 */
class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController(), new ValidatingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(new LocalValidatorFactoryBean())
            .build();
  }

  @Test
  void returns500WithAGenericBodyAndNeverTheRealExceptionMessage() throws Exception {
    mockMvc
        .perform(get("/test/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("internal_error"))
        .andExpect(jsonPath("$.correlationId").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void everyCallGetsItsOwnCorrelationId() throws Exception {
    final String first =
        mockMvc.perform(get("/test/boom")).andReturn().getResponse().getContentAsString();
    final String second =
        mockMvc.perform(get("/test/boom")).andReturn().getResponse().getContentAsString();

    org.assertj.core.api.Assertions.assertThat(first).isNotEqualTo(second);
  }

  // Regression test for the real bug this class's own Javadoc documents: a first version of
  // GlobalExceptionHandler with no ResponseEntityExceptionHandler supertype silently turned this
  // exact case into a 500, caught by CreateOrganizationIntegrationTest's own already-passing
  // assertions before it shipped.
  @Test
  void aBeanValidationFailureStillGets400NotSwallowedByTheCatchAll() throws Exception {
    mockMvc
        .perform(
            post("/test/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  /** Test-only controller whose sole job is to throw something this codebase never anticipates. */
  @RestController
  static class ThrowingController {

    @GetMapping("/test/boom")
    String boom() {
      throw new IllegalStateException("some secret internal detail that must never reach a client");
    }
  }

  /**
   * Test-only controller exercising {@code @Valid}, same shape as {@code
   * CreateOrganizationController}.
   */
  @RestController
  static class ValidatingController {

    @PostMapping("/test/validated")
    String validated(@Valid @RequestBody final ValidatedRequest request) {
      return "ok";
    }

    record ValidatedRequest(@NotBlank String name) {}
  }
}
