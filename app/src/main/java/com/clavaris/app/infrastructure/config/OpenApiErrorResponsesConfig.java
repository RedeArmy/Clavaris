package com.clavaris.app.infrastructure.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documents the error answers every operation can give, found by a Schemathesis run against {@code
 * /v3/api-docs}: it sent 636 generated requests, saw no server error, and reported 73 answers the
 * contract did not list (a malformed identifier gives 400, a missing right 403, an unknown resource
 * 404) plus a 400 that was checked against the success schema. Writing these on each controller
 * would repeat the same four lines on every method and drift; one customizer states them once.
 *
 * <p>Only what the server really does is listed. A 400 from Spring MVC (an identifier or a paging
 * limit that does not convert, a body that does not parse) is an RFC 9457 {@code problem+json};
 * 401, 403 and 404 come from the security chain or the controller with no body, so they carry a
 * description and no schema. An operation that already documents one of these codes keeps its own.
 */
@Configuration
public class OpenApiErrorResponsesConfig {

  static final String PROBLEM_JSON = "application/problem+json";

  /**
   * The management API is rate limited per caller (AdminApiRateLimitRules); other paths are not.
   */
  static final String RATE_LIMITED_PREFIX = "/api/";

  @Bean
  public OpenApiCustomizer errorResponsesCustomizer() {
    return openApi ->
        openApi
            .getPaths()
            .forEach(
                (path, item) ->
                    item.readOperations()
                        .forEach(
                            operation -> {
                              addMissingErrorResponses(operation);
                              if (path.startsWith(RATE_LIMITED_PREFIX)) {
                                addTooManyRequests(operation);
                              }
                            }));
  }

  static void addMissingErrorResponses(final Operation operation) {
    ApiResponses responses = operation.getResponses();
    if (responses == null) {
      responses = new ApiResponses();
      operation.setResponses(responses);
    }
    dropSuccessSchemaCopiedOntoErrors(responses);
    if (!responses.containsKey("400")) {
      responses.addApiResponse(
          "400",
          new ApiResponse()
              .description(
                  "The request could not be read: a malformed identifier, parameter or body")
              .content(
                  new Content()
                      .addMediaType(PROBLEM_JSON, new MediaType().schema(problemDetail()))));
    }
    if (!responses.containsKey("401")) {
      responses.addApiResponse("401", new ApiResponse().description("Not authenticated"));
    }
    if (!responses.containsKey("403")) {
      responses.addApiResponse(
          "403", new ApiResponse().description("Authenticated but not allowed"));
    }
    if (!responses.containsKey("404")) {
      responses.addApiResponse("404", new ApiResponse().description("No such resource"));
    }
  }

  static void addTooManyRequests(final Operation operation) {
    if (!operation.getResponses().containsKey("429")) {
      operation
          .getResponses()
          .addApiResponse(
              "429",
              new ApiResponse()
                  .description("Too many requests: retry after the Retry-After delay"));
    }
  }

  /**
   * springdoc fills an {@code @ApiResponse} that names no content with the method's return type, so
   * a documented 400 "Malformed cursor" ends up claiming the success body (an array of workspaces).
   * An error answer whose schema is exactly the success schema is that copy, never a real body: it
   * keeps the author's description and gets the real shape, a problem document for a 400 and no
   * body for the others.
   */
  private static void dropSuccessSchemaCopiedOntoErrors(final ApiResponses responses) {
    final Schema<?> success =
        responses.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("2"))
            .map(entry -> firstSchema(entry.getValue()))
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElse(null);
    if (success == null) {
      return;
    }
    responses.entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("4"))
        .filter(entry -> success.equals(firstSchema(entry.getValue())))
        .forEach(
            entry ->
                entry
                    .getValue()
                    .setContent(
                        "400".equals(entry.getKey())
                            ? new Content()
                                .addMediaType(PROBLEM_JSON, new MediaType().schema(problemDetail()))
                            : null));
  }

  private static Schema<?> firstSchema(final ApiResponse response) {
    if (response == null || response.getContent() == null) {
      return null;
    }
    return response.getContent().values().stream()
        .map(MediaType::getSchema)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  private static Schema<?> problemDetail() {
    final ObjectSchema schema = new ObjectSchema();
    schema.addProperty("type", new StringSchema());
    schema.addProperty("title", new StringSchema());
    schema.addProperty("status", new Schema<Integer>().type("integer").format("int32"));
    schema.addProperty("detail", new StringSchema());
    schema.addProperty("instance", new StringSchema());
    return schema;
  }
}
