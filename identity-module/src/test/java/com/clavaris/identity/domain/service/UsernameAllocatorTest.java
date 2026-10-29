package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.identity.domain.model.Username;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class UsernameAllocatorTest {

  private static Predicate<Username> takenAre(final String... names) {
    final Set<String> taken = Set.of(names);
    return username -> taken.contains(username.value());
  }

  @Test
  void usesTheOfferedNameWhenItIsFree() {
    assertThat(UsernameAllocator.allocate("Ada-Lovelace", takenAre()))
        .map(Username::value)
        .contains("ada-lovelace");
  }

  @Test
  void appendsTheFirstFreeNumberWhenTheNameIsTaken() {
    assertThat(UsernameAllocator.allocate("juan", takenAre("juan", "juan2")))
        .map(Username::value)
        .contains("juan3");
  }

  @Test
  void aTakenNameStaysDistinctInTheOtherCaseToo() {
    assertThat(UsernameAllocator.allocate("JUAN", takenAre("juan")))
        .map(Username::value)
        .contains("juan2");
  }

  @Test
  void givesUpWhenEveryCandidateIsTaken() {
    assertThat(UsernameAllocator.allocate("juan", username -> true)).isEmpty();
  }

  @Test
  void aNameTooLongIsCutToTheMaximumBeforeTheSuffixIsAdded() {
    final String longName = "a".repeat(39);
    final String cut = "a".repeat(32);

    assertThat(UsernameAllocator.allocate(longName, takenAre(cut)))
        .map(Username::value)
        .hasValueSatisfying(value -> assertThat(value).hasSize(32).endsWith("2"));
  }

  @Test
  void aSuffixNeverPushesTheNameOverTheMaximum() {
    final String cut = "b".repeat(32);

    assertThat(UsernameAllocator.allocate(cut, takenAre(cut)))
        .map(Username::value)
        .hasValueSatisfying(value -> assertThat(value.length()).isLessThanOrEqualTo(32));
  }

  @Test
  void aNameTheUsernameRuleRejectsYieldsNothingInsteadOfBeingRewritten() {
    assertThat(UsernameAllocator.allocate("ab", takenAre())).isEmpty();
    assertThat(UsernameAllocator.allocate("has space", takenAre())).isEmpty();
    assertThat(UsernameAllocator.allocate("with.dot", takenAre())).isEmpty();
  }

  @Test
  void nothingOfferedYieldsNothing() {
    assertThat(UsernameAllocator.allocate(null, takenAre())).isEmpty();
    assertThat(UsernameAllocator.allocate("   ", takenAre())).isEmpty();
  }
}
