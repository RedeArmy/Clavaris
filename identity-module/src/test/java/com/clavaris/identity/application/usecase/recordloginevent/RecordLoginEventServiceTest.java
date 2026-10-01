package com.clavaris.identity.application.usecase.recordloginevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.LoginEvent;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecordLoginEventServiceTest {

  private LoginEventRepository loginEvents;
  private RecordLoginEventService service;
  private AccountId accountId;
  private OrganizationId organizationId;

  @BeforeEach
  void setUp() {
    loginEvents = mock(LoginEventRepository.class);
    service = new RecordLoginEventService(loginEvents);
    accountId = AccountId.newId();
    organizationId = new OrganizationId(UUID.randomUUID());
  }

  @Test
  void insertsALoginEventForTheGivenAccountAndOrganization() {
    service.handle(new RecordLoginEventCommand(accountId, organizationId));

    ArgumentCaptor<LoginEvent> captured = ArgumentCaptor.forClass(LoginEvent.class);
    verify(loginEvents).insert(captured.capture());
    assertThat(captured.getValue().accountId()).isEqualTo(accountId);
    assertThat(captured.getValue().organizationId()).isEqualTo(organizationId);
  }

  // Same "never lets a side-channel write fail an otherwise-successful login" guarantee
  // RecordAccountLoginDeviceService's own Javadoc already establishes for this exact shape of
  // write — a failed insert here must never propagate and fail the login that triggered it.
  @Test
  void aFailedInsertNeverPropagates() {
    doThrow(new RuntimeException("Postgres hiccup")).when(loginEvents).insert(any());

    assertThatCode(() -> service.handle(new RecordLoginEventCommand(accountId, organizationId)))
        .doesNotThrowAnyException();
  }
}
