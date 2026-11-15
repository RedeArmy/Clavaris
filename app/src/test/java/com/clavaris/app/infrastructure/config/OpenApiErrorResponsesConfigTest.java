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

  @Test
  void theCustomizerAddsTheErrorAnswersToEveryPathAndTheRateLimitOnlyUnderApi() {
    io.swagger.v3.oas.models.OpenAPI openApi =
        new io.swagger.v3.oas.models.OpenAPI()
            .paths(
                new io.swagger.v3.oas.models.Paths()
                    .addPathItem(
                        "/api/v1/admin/things",
                        new io.swagger.v3.oas.models.PathItem().get(new Operation()))
                    .addPathItem(
                        "/o/{organizationId}/avatars/{accountId}",
                        new io.swagger.v3.oas.models.PathItem().get(new Operation())));

    new OpenApiErrorResponsesConfig().errorResponsesCustomizer().customise(openApi);

    Operation managementApi = openApi.getPaths().get("/api/v1/admin/things").getGet();
    Operation outsideIt =
        openApi.getPaths().get("/o/{organizationId}/avatars/{accountId}").getGet();
    assertThat(managementApi.getResponses()).containsKeys("400", "401", "403", "404", "429");
    assertThat(outsideIt.getResponses()).containsKeys("400", "401", "403", "404");
    assertThat(outsideIt.getResponses()).doesNotContainKey("429");
  }

  @Test
  void theProblemDocumentNamesTheFiveFieldsOfRfc9457WithTheirTypes() {
    Operation operation = new Operation();

    OpenApiErrorResponsesConfig.addMissingErrorResponses(operation);

    io.swagger.v3.oas.models.media.Schema<?> schema =
        operation
            .getResponses()
            .get("400")
            .getContent()
            .get(OpenApiErrorResponsesConfig.PROBLEM_JSON)
            .getSchema();
    assertThat(schema.getProperties())
        .containsOnlyKeys("type", "title", "status", "detail", "instance");
    assertThat(schema.getProperties().get("status").getType()).isEqualTo("integer");
    assertThat(schema.getProperties().get("title").getType()).isEqualTo("string");
  }

  @Test
  void anErrorAnswerWithItsOwnBodyKeepsItAndTheSuccessAnswerIsNeverRewritten() {
    io.swagger.v3.oas.models.media.Schema<?> success =
        new io.swagger.v3.oas.models.media.ArraySchema();
    io.swagger.v3.oas.models.media.Schema<?> own =
        new io.swagger.v3.oas.models.media.ObjectSchema();
    io.swagger.v3.oas.models.media.Content successBody =
        new io.swagger.v3.oas.models.media.Content()
            .addMediaType("*/*", new io.swagger.v3.oas.models.media.MediaType().schema(success));
    io.swagger.v3.oas.models.media.Content ownBody =
        new io.swagger.v3.oas.models.media.Content()
            .addMediaType("*/*", new io.swagger.v3.oas.models.media.MediaType().schema(own));
    // The 400 comes first, so a success lookup that did not stick to 2xx would pick up its schema.
    ApiResponses responses = new ApiResponses();
    responses.addApiResponse("400", new ApiResponse().description("Real body").content(ownBody));
    responses.addApiResponse("200", new ApiResponse().content(successBody));
    Operation operation = new Operation().responses(responses);

    OpenApiErrorResponsesConfig.addMissingErrorResponses(operation);

    assertThat(operation.getResponses().get("400").getContent()).isSameAs(ownBody);
    assertThat(operation.getResponses().get("200").getContent()).isSameAs(successBody);
  }
}
