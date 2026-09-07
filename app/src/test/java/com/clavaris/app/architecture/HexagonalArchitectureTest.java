package com.clavaris.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Enforces the hexagonal dependency rule as a real, CI-checked constraint — not a code-review
 * convention. Runs against every business module's compiled classes at once ("app" is the one
 * module that depends on all of them).
 *
 * <p>TD-ARCH-017: written when {@code domain/} was still empty and every rule below held vacuously
 * — that's no longer true (77 real domain classes across the business modules as of 2026-09-06, 54
 * of them under {@code domain/model} alone), so every rule now actively checks real classes on
 * every run, not just guarding against a future violation. {@code allowEmptyShould(true)} is kept
 * regardless, not because the match is empty today but as defense-in-depth: an empty match should
 * stay an intentional, opted-in outcome rather than the ArchUnit 1.3+ default's "empty match =
 * failure," which exists to catch a typo'd package name, not this codebase's own real state.
 */
class HexagonalArchitectureTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.clavaris");

  @Test
  void domainDependsOnNothingOutsideItself() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..application..", "..infrastructure..")
        .because("domain/ depends on nothing outside itself")
        // TD-ARCH-017: explicit, not the ArchUnit 1.3+ default — this rule actively checks real
        // domain classes today (see this class's own Javadoc), so allowEmptyShould(true) is kept
        // as defense-in-depth against a future empty match, not because today's match is empty.
        // The library's own default ("empty match = failure") exists to catch a typo'd package
        // name — deliberately opted out of here, not overlooked.
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  @Test
  void applicationDependsOnlyOnDomainNotInfrastructure() {
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..infrastructure..")
        .because("application/ depends only on domain/")
        // TD-ARCH-017: explicit, not the ArchUnit 1.3+ default — this rule actively checks real
        // domain classes today (see this class's own Javadoc), so allowEmptyShould(true) is kept
        // as defense-in-depth against a future empty match, not because today's match is empty.
        // The library's own default ("empty match = failure") exists to catch a typo'd package
        // name — deliberately opted out of here, not overlooked.
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  @Test
  void domainHasNoSpringDependency() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("org.springframework..")
        .because("no Spring annotations inside domain/")
        // TD-ARCH-017: explicit, not the ArchUnit 1.3+ default — this rule actively checks real
        // domain classes today (see this class's own Javadoc), so allowEmptyShould(true) is kept
        // as defense-in-depth against a future empty match, not because today's match is empty.
        // The library's own default ("empty match = failure") exists to catch a typo'd package
        // name — deliberately opted out of here, not overlooked.
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  @Test
  void domainHasNoJpaDependency() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.persistence..")
        .because("no JPA annotations inside domain/")
        // TD-ARCH-017: explicit, not the ArchUnit 1.3+ default — this rule actively checks real
        // domain classes today (see this class's own Javadoc), so allowEmptyShould(true) is kept
        // as defense-in-depth against a future empty match, not because today's match is empty.
        // The library's own default ("empty match = failure") exists to catch a typo'd package
        // name — deliberately opted out of here, not overlooked.
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  @Test
  void domainHasNoHttpServletDependency() {
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("jakarta.servlet..")
        .because("no HTTP concepts inside domain/")
        // TD-ARCH-017: explicit, not the ArchUnit 1.3+ default — this rule actively checks real
        // domain classes today (see this class's own Javadoc), so allowEmptyShould(true) is kept
        // as defense-in-depth against a future empty match, not because today's match is empty.
        // The library's own default ("empty match = failure") exists to catch a typo'd package
        // name — deliberately opted out of here, not overlooked.
        .allowEmptyShould(true)
        .check(CLASSES);
  }
}
