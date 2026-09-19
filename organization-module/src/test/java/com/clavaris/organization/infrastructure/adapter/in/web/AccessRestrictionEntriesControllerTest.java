package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryUseCase;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.DuplicateAccessRestrictionEntryException;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.AccessRestrictionEntryNotFoundException;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryUseCase;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same pattern as RegisterOAuthClientControllerTest. */
class AccessRestrictionEntriesControllerTest {

  private final UUID organizationId = UUID.randomUUID();

  private AddAccessRestrictionEntryUseCase addEntry;
  private RemoveAccessRestrictionEntryUseCase removeEntry;
  private ListAccessRestrictionEntriesForOrganizationUseCase listEntries;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    addEntry = mock(AddAccessRestrictionEntryUseCase.class);
    removeEntry = mock(RemoveAccessRestrictionEntryUseCase.class);
    listEntries = mock(ListAccessRestrictionEntriesForOrganizationUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new AccessRestrictionEntriesController(addEntry, removeEntry, listEntries))
            .build();
  }

  private String basePath() {
    return "/api/v1/admin/organizations/" + organizationId + "/access-restrictions";
  }

  private AccessRestrictionEntry sampleEntry() {
    return AccessRestrictionEntry.create(
        organizationId, RestrictionType.BLOCKLIST, "blocked@example.com");
  }

  @Test
  void listsTheOrganizationsEntries() throws Exception {
    when(listEntries.handle(any())).thenReturn(List.of(sampleEntry()));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].identifier").value("blocked@example.com"))
        .andExpect(jsonPath("$[0].type").value("BLOCKLIST"));
  }

  @Test
  void returnsAnEmptyListWhenTheOrganizationHasNoEntries() throws Exception {
    when(listEntries.handle(any())).thenReturn(List.of());

    mockMvc.perform(get(basePath())).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void addReturns201WithTheCreatedEntry() throws Exception {
    when(addEntry.handle(any())).thenReturn(sampleEntry());

    mockMvc
        .perform(
            post(basePath())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"BLOCKLIST\",\"identifier\":\"blocked@example.com\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.identifier").value("blocked@example.com"))
        .andExpect(jsonPath("$.organizationId").value(organizationId.toString()));
  }

  @Test
  void addReturns409WhenTheIdentifierAlreadyExists() throws Exception {
    doThrow(new DuplicateAccessRestrictionEntryException("blocked@example.com"))
        .when(addEntry)
        .handle(any());

    mockMvc
        .perform(
            post(basePath())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"BLOCKLIST\",\"identifier\":\"blocked@example.com\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void addWithNoIdentifierReturns400() throws Exception {
    mockMvc
        .perform(
            post(basePath())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"BLOCKLIST\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void removeReturns204OnSuccess() throws Exception {
    UUID entryId = UUID.randomUUID();

    mockMvc.perform(delete(basePath() + "/" + entryId)).andExpect(status().isNoContent());
  }

  @Test
  void removeReturns404WhenTheEntryDoesNotExist() throws Exception {
    UUID entryId = UUID.randomUUID();
    doThrow(new AccessRestrictionEntryNotFoundException(entryId)).when(removeEntry).handle(any());

    mockMvc.perform(delete(basePath() + "/" + entryId)).andExpect(status().isNotFound());
  }
}
