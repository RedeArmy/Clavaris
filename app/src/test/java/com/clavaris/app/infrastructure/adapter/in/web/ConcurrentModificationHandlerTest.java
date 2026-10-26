package com.clavaris.app.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.ConcurrentAccountModificationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** TD-ARCH-037: a lost race on an account is a 409, and says nothing about which account. */
class ConcurrentModificationHandlerTest {

  @Test
  void answers409WithAFixedMessageAndNoAccountId() {
    final AccountId accountId = AccountId.newId();

    final ResponseEntity<ConcurrentModificationHandler.ConflictResponse> response =
        new ConcurrentModificationHandler()
            .handleConcurrentAccountModification(
                new ConcurrentAccountModificationException(accountId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isEqualTo("concurrent_modification");
    assertThat(response.getBody().message()).doesNotContain(accountId.value().toString());
    assertThat(response.getBody().correlationId()).isNotBlank();
  }
}
