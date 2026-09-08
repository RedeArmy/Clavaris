package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Dedicated coverage for {@link IdempotentResponse}'s own explicit {@code equals}/{@code
 * hashCode}/{@code toString} — see its own Javadoc for why a record's generated versions weren't
 * enough here (an array field compared by reference, not content).
 */
class IdempotentResponseTest {

  @Test
  void isEqualToItself() {
    IdempotentResponse response = new IdempotentResponse(200, "application/json", "{}".getBytes());

    assertThat(response).isEqualTo(response);
  }

  @Test
  void isEqualToAnotherInstanceWithTheSameContentEvenAsADifferentArrayInstance() {
    IdempotentResponse first =
        new IdempotentResponse(201, "application/json", "{\"a\":1}".getBytes());
    IdempotentResponse second =
        new IdempotentResponse(201, "application/json", "{\"a\":1}".getBytes());

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void isNotEqualWhenStatusDiffers() {
    IdempotentResponse first = new IdempotentResponse(200, "application/json", "{}".getBytes());
    IdempotentResponse second = new IdempotentResponse(201, "application/json", "{}".getBytes());

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isNotEqualWhenContentTypeDiffers() {
    IdempotentResponse first = new IdempotentResponse(200, "application/json", "{}".getBytes());
    IdempotentResponse second = new IdempotentResponse(200, "text/plain", "{}".getBytes());

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void isNotEqualWhenBodyContentDiffers() {
    IdempotentResponse first =
        new IdempotentResponse(200, "application/json", "{\"a\":1}".getBytes());
    IdempotentResponse second =
        new IdempotentResponse(200, "application/json", "{\"a\":2}".getBytes());

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void twoNullContentTypesAreEqual() {
    IdempotentResponse first = new IdempotentResponse(204, null, null);
    IdempotentResponse second = new IdempotentResponse(204, null, null);

    assertThat(first).isEqualTo(second);
    assertThat(first.hashCode()).isEqualTo(second.hashCode());
  }

  @Test
  void isNeverEqualToAnUnrelatedType() {
    IdempotentResponse response = new IdempotentResponse(200, "application/json", "{}".getBytes());

    assertThat(response).isNotEqualTo("not an IdempotentResponse");
    assertThat(response).isNotEqualTo(null);
  }

  @Test
  void toStringIncludesEveryFieldForDiagnosability() {
    IdempotentResponse response = new IdempotentResponse(201, "application/json", "{}".getBytes());

    assertThat(response.toString())
        .contains("status=201")
        .contains("contentType=application/json")
        .contains("body=");
  }
}
