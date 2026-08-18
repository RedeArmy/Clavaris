package com.clavaris.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Enforces CLAUDE.md §7.2's dependency rule as a real, CI-checked constraint — not a code-review
 * convention. Runs against every business module's compiled classes at once ("app" is the one
 * module that depends on all of them).
 *
 * <p>On zero domain classes (current state, CLAUDE.md §11), every rule below holds vacuously — the
 * point is that the guardrail exists from day one, so the FIRST class ever added to domain/ is
 * already checked, not the hundredth.
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
        .because("CLAUDE.md §7.2: domain/ depends on nothing outside itself")
        // Explicit, not the ArchUnit 1.3+ default: with zero domain classes today
        // (CLAUDE.md §11), this rule matches nothing yet. allowEmptyShould(true)
        // makes that an intentional vacuous pass instead of the library's default
        // "empty match = failure" (a real safety feature for catching a typo'd
        // package name — deliberately opted out of here, not overlooked).
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
        .because("CLAUDE.md §7.2: application/ depends only on domain/")
        // Explicit, not the ArchUnit 1.3+ default: with zero domain classes today
        // (CLAUDE.md §11), this rule matches nothing yet. allowEmptyShould(true)
        // makes that an intentional vacuous pass instead of the library's default
        // "empty match = failure" (a real safety feature for catching a typo'd
        // package name — deliberately opted out of here, not overlooked).
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
        .because("CLAUDE.md §7.2: no Spring annotations inside domain/")
        // Explicit, not the ArchUnit 1.3+ default: with zero domain classes today
        // (CLAUDE.md §11), this rule matches nothing yet. allowEmptyShould(true)
        // makes that an intentional vacuous pass instead of the library's default
        // "empty match = failure" (a real safety feature for catching a typo'd
        // package name — deliberately opted out of here, not overlooked).
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
        .because("CLAUDE.md §7.2: no JPA annotations inside domain/")
        // Explicit, not the ArchUnit 1.3+ default: with zero domain classes today
        // (CLAUDE.md §11), this rule matches nothing yet. allowEmptyShould(true)
        // makes that an intentional vacuous pass instead of the library's default
        // "empty match = failure" (a real safety feature for catching a typo'd
        // package name — deliberately opted out of here, not overlooked).
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
        .because("CLAUDE.md §7.2: no HTTP concepts inside domain/")
        // Explicit, not the ArchUnit 1.3+ default: with zero domain classes today
        // (CLAUDE.md §11), this rule matches nothing yet. allowEmptyShould(true)
        // makes that an intentional vacuous pass instead of the library's default
        // "empty match = failure" (a real safety feature for catching a typo'd
        // package name — deliberately opted out of here, not overlooked).
        .allowEmptyShould(true)
        .check(CLASSES);
  }
}
