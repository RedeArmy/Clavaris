package com.clavaris.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.clavaris.identity.application.usecase.registeraccount.AccessRestrictionPolicyProvider;
import com.clavaris.identity.domain.model.Account;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * TD-SEC-084: an Organization's blocklist and allowlist were enforced at registration and by an
 * operator creating an Account, and nowhere else, so a provider sign-up and an email change walked
 * around them. A policy is only as strong as its weakest entry point, so the rule is structural:
 * every use case that puts a tenant Account's email into the system, whether by creating the
 * Account or by changing its address, must hold the {@link AccessRestrictionPolicyProvider}.
 *
 * <p>This proves the policy is <i>consulted</i>, not that it is consulted correctly; each use case
 * has its own tests for the second. What it prevents is a sixth path that never asks. The rule
 * would pass vacuously on a renamed factory method, so the matching sets are asserted non-empty by
 * ArchUnit's own default (an empty match fails the rule).
 */
class OrganizationPolicyCoverageTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.clavaris");

  private static final Set<String> ENTRY_POINTS =
      Set.of("register", "registerPendingApproval", "changeEmail");

  private static final DescribedPredicate<JavaClass> PUTS_AN_EMAIL_INTO_AN_ORGANIZATION =
      DescribedPredicate.describe(
          "creates a tenant Account or changes its email",
          javaClass ->
              javaClass.getPackageName().contains(".application.usecase.")
                  && javaClass.getMethodCallsFromSelf().stream()
                      .anyMatch(
                          call ->
                              call.getTargetOwner().isEquivalentTo(Account.class)
                                  && ENTRY_POINTS.contains(call.getName())));

  private static final ArchCondition<JavaClass> HOLD_THE_ACCESS_RESTRICTION_POLICY =
      new ArchCondition<>("hold the AccessRestrictionPolicyProvider") {
        @Override
        public void check(final JavaClass javaClass, final ConditionEvents events) {
          final boolean holds =
              javaClass.getDirectDependenciesFromSelf().stream()
                  .anyMatch(
                      dependency ->
                          dependency
                              .getTargetClass()
                              .isEquivalentTo(AccessRestrictionPolicyProvider.class));
          if (!holds) {
            events.add(
                SimpleConditionEvent.violated(
                    javaClass,
                    javaClass.getName()
                        + " puts an email into an Organization but never asks its access lists"));
          }
        }
      };

  @Test
  void everyPathThatPutsAnEmailIntoAnOrganizationAsksItsAccessLists() {
    classes()
        .that(PUTS_AN_EMAIL_INTO_AN_ORGANIZATION)
        .should(HOLD_THE_ACCESS_RESTRICTION_POLICY)
        .because(
            "TD-SEC-084: the Organization's blocklist and allowlist must cover every way an email"
                + " enters it, not only the password registration")
        .check(CLASSES);
  }
}
