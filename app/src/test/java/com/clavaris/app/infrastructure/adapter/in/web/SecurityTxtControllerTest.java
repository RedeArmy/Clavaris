package com.clavaris.app.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SecurityTxtControllerTest {

  private static final String FUTURE = Instant.now().plus(300, ChronoUnit.DAYS).toString();
  private static final String BASE = "https://id.example.com";

  private static MockMvc mvc(
      final String contact, final String expires, final String policy, final String languages) {
    return MockMvcBuilders.standaloneSetup(
            new SecurityTxtController(contact, expires, policy, languages, BASE))
        .build();
  }

  @Test
  void publishesTheRequiredFieldsAndTheOptionalOnesWhenConfigured() throws Exception {
    mvc("mailto:security@example.com", FUTURE, "https://example.com/disclosure", "en, es")
        .perform(get("/.well-known/security.txt"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/plain"))
        .andExpect(content().string(containsString("Contact: mailto:security@example.com\n")))
        .andExpect(content().string(containsString("Expires: " + FUTURE + "\n")))
        .andExpect(content().string(containsString("Policy: https://example.com/disclosure\n")))
        .andExpect(content().string(containsString("Preferred-Languages: en, es\n")))
        .andExpect(
            content().string(containsString("Canonical: " + BASE + "/.well-known/security.txt")));
  }

  @Test
  void isNotPublishedUntilAContactIsConfigured() throws Exception {
    mvc("", FUTURE, "", "en")
        .perform(get("/.well-known/security.txt"))
        .andExpect(status().isNotFound());
  }

  @Test
  void anExpiredFileIsNotPublished() throws Exception {
    mvc("mailto:security@example.com", "2020-01-01T00:00:00Z", "", "en")
        .perform(get("/.well-known/security.txt"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aMissingOrUnparseableExpiryIsNotPublished() throws Exception {
    mvc("mailto:security@example.com", "", "", "en")
        .perform(get("/.well-known/security.txt"))
        .andExpect(status().isNotFound());
    mvc("mailto:security@example.com", "next year", "", "en")
        .perform(get("/.well-known/security.txt"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aValueCannotInjectAnotherField() throws Exception {
    mvc("mailto:a@example.com\nContact: mailto:attacker@example.com", FUTURE, "", "en")
        .perform(get("/.well-known/security.txt"))
        .andExpect(status().isNotFound());
    mvc("mailto:a@example.com", FUTURE, "https://x.example\nPolicy: https://evil", "en, es\nX: y")
        .perform(get("/.well-known/security.txt"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("evil"))))
        .andExpect(content().string(not(containsString("X: y"))));
  }

  @Test
  void theShortPathAnswersToo() throws Exception {
    mvc("https://example.com/security", FUTURE, "", "en")
        .perform(get("/security.txt"))
        .andExpect(status().isOk());
  }
}
