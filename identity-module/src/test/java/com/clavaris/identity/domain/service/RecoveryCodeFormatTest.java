package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecoveryCodeFormatTest {

  @Test
  void aCodeIsSixteenBase32CharactersInGroupsOfFour() {
    assertThat(RecoveryCodeFormat.generate()).matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){3}");
  }

  @Test
  void typingItWithoutHyphensOrInLowerCaseStillNormalisesToTheSameCode() {
    final String code = RecoveryCodeFormat.generate();
    final String bare = code.replace("-", "").toLowerCase(java.util.Locale.ROOT);

    assertThat(RecoveryCodeFormat.normalize(bare)).isEqualTo(RecoveryCodeFormat.normalize(code));
    assertThat(RecoveryCodeFormat.normalize(code.replace("-", " ")))
        .isEqualTo(RecoveryCodeFormat.normalize(code));
  }

  @Test
  void anythingThatCannotBeACodeNormalisesToNull() {
    assertThat(RecoveryCodeFormat.normalize(null)).isNull();
    assertThat(RecoveryCodeFormat.normalize("ABCD-EFGH")).isNull();
    assertThat(RecoveryCodeFormat.normalize("1111-1111-1111-1111")).isNull();
    assertThat(RecoveryCodeFormat.normalize("ABCD-EFGH-IJKL-MNOP-QRST")).isNull();
  }

  @Test
  void aSetHasTenDistinctCodesAndAHashForEach() {
    final RecoveryCodeFormat.Set set = RecoveryCodeFormat.newSet();

    assertThat(set.codes()).hasSize(10).doesNotHaveDuplicates();
    assertThat(set.hashes()).hasSize(10).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
    assertThat(set.hashes().get(0))
        .isEqualTo(RecoveryCodeFormat.hash(RecoveryCodeFormat.normalize(set.codes().get(0))));
  }

  @Test
  void theHashNeverContainsTheCode() {
    final String code = RecoveryCodeFormat.generate();
    assertThat(RecoveryCodeFormat.hash(RecoveryCodeFormat.normalize(code)))
        .doesNotContain(code.replace("-", ""));
  }
}
