package com.clavaris.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0008 §3: springdoc generates the management API's OpenAPI spec (and Swagger UI) from
 * controller annotations, not a hand-maintained spec file — that guarantee only holds if every
 * endpoint actually carries {@code @Operation}. Without a check like this, a new
 * {@code @RestController} endpoint added without it is silently invisible at {@code /v3/api-docs},
 * quietly breaking the project's own "integrate in under a day" goal for whoever explores the API
 * next.
 *
 * <p>Runs against every business module's compiled classes at once (same rationale as {@link
 * HexagonalArchitectureTest}) — {@code app} is the one module that depends on all of them.
 * Deliberately scoped to {@code @RestController} only, not plain {@code @Controller}: ADR-0008 §3
 * is explicit that the hosted login/consent UI (Thymeleaf, server-rendered) and the OIDC surface
 * are NOT part of this Swagger-documented contract — {@code RegisterAccountController} (a plain
 * {@code @Controller}) is correctly out of scope for this rule, not an oversight.
 */
class OpenApiDocumentationTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.clavaris");

  private static final DescribedPredicate<JavaMethod> IS_REST_CONTROLLER_ENDPOINT_METHOD =
      DescribedPredicate.describe(
          "is an HTTP-mapped method on a @RestController (management API, ADR-0008 §3)",
          method ->
              method.getOwner().isAnnotatedWith(RestController.class)
                  && (method.isAnnotatedWith(GetMapping.class)
                      || method.isAnnotatedWith(PostMapping.class)
                      || method.isAnnotatedWith(PutMapping.class)
                      || method.isAnnotatedWith(DeleteMapping.class)
                      || method.isAnnotatedWith(PatchMapping.class)
                      || method.isAnnotatedWith(RequestMapping.class)));

  @Test
  void everyRestControllerEndpointIsDocumentedWithAnOpenApiOperation() {
    methods()
        .that(IS_REST_CONTROLLER_ENDPOINT_METHOD)
        .should()
        .beAnnotatedWith(Operation.class)
        .because(
            "ADR-0008 §3: the management API's OpenAPI/Swagger UI is generated from controller "
                + "annotations, not hand-maintained — an endpoint missing @Operation never appears "
                + "there, which is a silent documentation gap, not a build failure, unless this "
                + "check exists to catch it. Explicit, not the ArchUnit 1.3+ default: with zero "
                + "matching methods this rule would otherwise vacuously pass on a typo'd predicate, "
                + "same reasoning as HexagonalArchitectureTest's own allowEmptyShould(true) — except "
                + "here at least one @RestController endpoint already exists (CreateOrganizationController), "
                + "so an empty match is itself a signal something regressed, not a valid steady state.")
        .check(CLASSES);
  }
}
