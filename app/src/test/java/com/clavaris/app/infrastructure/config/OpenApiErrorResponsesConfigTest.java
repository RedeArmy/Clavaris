package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

class OpenApiErrorResponsesConfigTest {

  @Test
  void anOperationWithOnlyASuccessAnswerGainsTheFourErrorAnswers() {
    Operation operation =
        new Operation().responses(new ApiResponses().addApiResponse("200", new ApiResponse()));

    OpenApiErrorResponsesConfig.addMissingErrorResponses(operation);

    assertThat(operation.getResponses()).containsKeys("200", "400", "401", "403", "404");
  }

  @Test
  void theBadRequestAnswerIsProblemJsonAndTheOthersCarryNoBody() {
    Operation operation = new Operation();

    OpenApiErrorResponsesConfig.addMissingErrorResponses(operation);

    assertThat(operation.getResponses().get("400").getContent())
        .containsKey(OpenApiErrorResponsesConfig.PROBLEM_JSON);
    assertThat(operation.getResponses().get("401").getContent()).isNull();
    assertThat(operation.getResponses().get("404").getContent()).isNull();
  }

  @Test
  void anAnswerTheOperationAlreadyDocumentsIsLeftAlone() {
    ApiResponse own = new ApiResponse().description("Organization not found");
    Operation operation = new Operation().responses(new ApiResponses().addApiResponse("404", own));

    OpenApiErrorResponsesConfig.addMissingErrorResponses(operation);

    assertThat(operation.getResponses().get("404")).isSameAs(own);
  }

  @Test
  void aSuccessBodyCopiedOntoADocumentedBadRequestIsReplacedByAProblemDocument() {
    io.swagger.v3.oas.models.media.Schema<?> success =
        new io.swagger.v3.oas.models.media.ArraySchema();
    io.swagger.v3.oas.models.media.Content body =
        new io.swagger.v3.oas.models.media.Content()
            .addMediaType("*/*", new io.swagger.v3.oas.models.media.MediaType().schema(success));
    Operation operation =
        new Operation()
            .responses(
                new ApiResponses()
                    .addApiResponse("200", new ApiResponse().content(body))
                    .addApiResponse(
                        "400", new ApiResponse().description("Malformed cursor").content(body)));

    OpenApiErrorResponsesConfig.addMissingErrorResponses(operation);

    ApiResponse badRequest = operation.getResponses().get("400");
    assertThat(badRequest.getDescription()).isEqualTo("Malformed cursor");
    assertThat(badRequest.getContent()).containsOnlyKeys(OpenApiErrorResponsesConfig.PROBLEM_JSON);
  }

  @Test
  void theRateLimitedAnswerIsAddedWhenAskedAndNeverOverwritten() {
    Operation operation = new Operation();
    OpenApiErrorResponsesConfig.addMissingErrorResponses(operation);

    OpenApiErrorResponsesConfig.addTooManyRequests(operation);
    ApiResponse first = operation.getResponses().get("429");
    OpenApiErrorResponsesConfig.addTooManyRequests(operation);

    assertThat(first).isNotNull();
    assertThat(operation.getResponses().get("429")).isSameAs(first);
  }
}
