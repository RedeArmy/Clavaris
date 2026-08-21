package com.clavaris.identity.application.usecase.activateplatformsigningkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.PlatformSigningKey;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActivatePlatformSigningKeyServiceTest {

  @Test
  void activatesANewKeyWhenNoneWasActiveBefore() {
    PlatformSigningKeyRepository repository = mock(PlatformSigningKeyRepository.class);
    when(repository.findActive()).thenReturn(Optional.empty());
    ActivatePlatformSigningKeyService service = new ActivatePlatformSigningKeyService(repository);

    PlatformSigningKey activated = service.handle("new-kid", "RS256");

    assertThat(activated.kid()).isEqualTo("new-kid");
    assertThat(activated.retiredAt()).isEmpty();
    verify(repository, times(1)).save(any());
  }

  @Test
  void retiresThePreviouslyActiveKeyBeforeActivatingADifferentOne() {
    PlatformSigningKeyRepository repository = mock(PlatformSigningKeyRepository.class);
    PlatformSigningKey previouslyActive = PlatformSigningKey.activate("old-kid", "RS256");
    when(repository.findActive()).thenReturn(Optional.of(previouslyActive));
    ActivatePlatformSigningKeyService service = new ActivatePlatformSigningKeyService(repository);

    service.handle("new-kid", "RS256");

    assertThat(previouslyActive.retiredAt()).isPresent();
    verify(repository, times(2)).save(any()); // once for the retired old key, once for the new one
  }

  @Test
  void reactivatingTheAlreadyActiveKidIsANoOp() {
    // TD-SEC-002: PlatformSigningKeyMaterial now reloads persisted key material on restart and
    // hands the *same* kid back to this service — retiring and immediately recreating that same
    // row every startup would misrepresent the row's real activeFrom history for no reason.
    PlatformSigningKeyRepository repository = mock(PlatformSigningKeyRepository.class);
    PlatformSigningKey alreadyActive = PlatformSigningKey.activate("same-kid", "RS256");
    when(repository.findActive()).thenReturn(Optional.of(alreadyActive));
    ActivatePlatformSigningKeyService service = new ActivatePlatformSigningKeyService(repository);

    PlatformSigningKey result = service.handle("same-kid", "RS256");

    assertThat(result).isSameAs(alreadyActive);
    assertThat(alreadyActive.retiredAt()).isEmpty();
    verify(repository, never()).save(any());
  }
}
