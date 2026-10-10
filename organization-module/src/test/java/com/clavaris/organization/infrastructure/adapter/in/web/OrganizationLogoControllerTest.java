package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.getorganizationlogo.GetOrganizationLogoUseCase;
import com.clavaris.organization.domain.model.OrganizationLogo;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The logo is public and user-supplied, so what matters here is what the response is allowed to do:
 * be an image of the stored type, never be sniffed into something else, and run nothing.
 */
class OrganizationLogoControllerTest {

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};

  private final UUID organizationId = UUID.randomUUID();
  private final GetOrganizationLogoUseCase getLogo = mock(GetOrganizationLogoUseCase.class);
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new OrganizationLogoController(getLogo)).build();

  private void organizationHasALogo() {
    when(getLogo.handle(organizationId))
        .thenReturn(Optional.of(OrganizationLogo.of(organizationId, "image/png", PNG)));
  }

  private String path() {
    return "/o/" + organizationId + "/branding/logo";
  }

  @Test
  void servesTheStoredImageWithItsOwnType() throws Exception {
    organizationHasALogo();

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(content().bytes(PNG));
  }

  // The file is user-supplied and served to the public: the browser may not guess another type, and
  // the response is allowed to load and run nothing at all.
  @Test
  void theResponseCannotBeSniffedIntoSomethingElseOrRunAnything() throws Exception {
    organizationHasALogo();

    mockMvc
        .perform(get(path()))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
  }

  // The URL carries the time the logo was set, so a versioned one may be kept for as long as a
  // browser likes; an unversioned one cannot outlive a replacement for long.
  @Test
  void aVersionedUrlIsCachedForALongTimeAndAnUnversionedOneForAShortTime() throws Exception {
    organizationHasALogo();

    final String versioned =
        mockMvc
            .perform(get(path()).param("v", "1760097600000"))
            .andReturn()
            .getResponse()
            .getHeader("Cache-Control");
    final String unversioned =
        mockMvc.perform(get(path())).andReturn().getResponse().getHeader("Cache-Control");

    assertThat(versioned).contains("public").contains("max-age=2592000").contains("immutable");
    assertThat(unversioned).contains("public").contains("max-age=300").doesNotContain("immutable");
  }

  @Test
  void aBlankVersionCountsAsNone() throws Exception {
    organizationHasALogo();

    final String cache =
        mockMvc
            .perform(get(path()).param("v", " "))
            .andReturn()
            .getResponse()
            .getHeader("Cache-Control");

    assertThat(cache).contains("max-age=300").doesNotContain("immutable");
  }

  @Test
  void anOrganizationWithNoLogoIsNotFound() throws Exception {
    when(getLogo.handle(organizationId)).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }
}
