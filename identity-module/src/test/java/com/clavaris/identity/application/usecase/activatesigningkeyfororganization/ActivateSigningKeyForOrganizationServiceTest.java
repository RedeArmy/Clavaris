package com.clavaris.identity.application.usecase.activatesigningkeyfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SigningKey;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ActivateSigningKeyForOrganizationServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  @Test
  void activatesANewKeyWhenNoneWasActiveBefore() {
    // BR-ORG-06: CreateOrganization's own case — a brand-new Organization has no prior key.
    SigningKeyRepository repository = mock(SigningKeyRepository.class);
    when(repository.findActive(organizationId)).thenReturn(Optional.empty());
    ActivateSigningKeyForOrganizationService service =
        new ActivateSigningKeyForOrganizationService(repository);

    SigningKey activated = service.handle(organizationId, "new-kid", "RS256");

    assertThat(activated.organizationId()).isEqualTo(organizationId);
    assertThat(activated.kid()).isEqualTo("new-kid");
    assertThat(activated.retiredAt()).isEmpty();
    verify(repository, times(1)).save(any());
  }

  @Test
  void retiresThePreviouslyActiveKeyBeforeActivatingTheNewOne() {
    // ADR-0010 §5.2: this same service doubles, unchanged, as the future manual-rotation
    // operation — this is the path that exercises.
    SigningKeyRepository repository = mock(SigningKeyRepository.class);
    SigningKey previouslyActive = SigningKey.activate(organizationId, "old-kid", "RS256");
    when(repository.findActive(organizationId)).thenReturn(Optional.of(previouslyActive));
    ActivateSigningKeyForOrganizationService service =
        new ActivateSigningKeyForOrganizationService(repository);

    service.handle(organizationId, "new-kid", "RS256");

    assertThat(previouslyActive.retiredAt()).isPresent();
    verify(repository, times(2)).save(any()); // once for the retired old key, once for the new one
  }

  @Test
  void neverRetiresAnotherOrganizationsActiveKey() {
    // BR-ORG-01/BR-ORG-04: each Organization's key lifecycle is fully isolated — findActive is
    // always scoped by the exact OrganizationId passed in, never a global "any active key" query.
    SigningKeyRepository repository = mock(SigningKeyRepository.class);
    ActivateSigningKeyForOrganizationService service =
        new ActivateSigningKeyForOrganizationService(repository);

    service.handle(organizationId, "new-kid", "RS256");

    verify(repository).findActive(organizationId);
  }
}
