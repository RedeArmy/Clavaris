package com.clavaris.identity.application.usecase.listsigningkeysfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.activatesigningkeyfororganization.SigningKeyRepository;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ListSigningKeysForOrganizationServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final SigningKeyRepository signingKeys = mock(SigningKeyRepository.class);
  private final ListSigningKeysForOrganizationService service =
      new ListSigningKeysForOrganizationService(signingKeys, Duration.ofHours(24));

  @Test
  void delegatesToFindActiveAndRetiredSinceWithACutoffMatchingTheConfiguredOverlapWindow() {
    SigningKey key = SigningKey.activate(organizationId, "a-kid", "RS256");
    when(signingKeys.findActiveAndRetiredSince(eq(organizationId), any())).thenReturn(List.of(key));

    List<SigningKey> found = service.handle(organizationId);

    assertThat(found).containsExactly(key);
    // The real, load-bearing behavior: the cutoff passed to the repository must actually reflect
    // the injected 24h overlap window, not some other arbitrary value.
    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(signingKeys).findActiveAndRetiredSince(eq(organizationId), cutoffCaptor.capture());
    assertThat(cutoffCaptor.getValue())
        .isCloseTo(Instant.now().minus(Duration.ofHours(24)), within(Duration.ofSeconds(5)));
  }

  @Test
  void anEmptyListIsAValidAnswer() {
    when(signingKeys.findActiveAndRetiredSince(eq(organizationId), any())).thenReturn(List.of());

    assertThat(service.handle(organizationId)).isEmpty();
  }
}
