package com.clavaris.organization.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * TD-ARCH-007: proves {@link JpaOrganizationEventOutboxWriter}'s own {@code JacksonException} ->
 * {@code IllegalStateException} translation actually fires, and that a failed serialization never
 * reaches the repository — the one branch {@code DeleteOrganizationIntegrationTest}'s own
 * real-Postgres happy path can't exercise (it never hands this writer a payload Jackson can't
 * serialize).
 */
class JpaOrganizationEventOutboxWriterTest {

  @Test
  void wrapsASerializationFailureInAnIllegalStateExceptionWithoutTouchingTheRepository() {
    SpringDataOrganizationEventOutboxJpaRepository outbox =
        mock(SpringDataOrganizationEventOutboxJpaRepository.class);
    // A real ObjectMapper (no custom module registered), not a mocked one — ObjectMapper itself
    // is this class's actual collaborator, not an abstraction worth mocking. A getter that itself
    // throws is Jackson's own real, documented way for property access to fail mid-serialization;
    // Jackson wraps it as a genuine JacksonException, the same as any real serialization failure.
    JpaOrganizationEventOutboxWriter writer =
        new JpaOrganizationEventOutboxWriter(outbox, new ObjectMapper());

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () -> writer.write("organization.deleted", UUID.randomUUID(), new Unserializable()))
        .withMessageContaining("organization.deleted");

    verifyNoInteractions(outbox);
  }

  /** A getter that throws mid-serialization — Jackson wraps that as a real JacksonException. */
  private static final class Unserializable {
    public String getValue() {
      throw new IllegalArgumentException("boom");
    }
  }
}
