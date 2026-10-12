package org.triplea.tools.lspjava;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SymbolQueryTest {

  @Test
  void bareTypeNameHasNoMember() {
    SymbolQuery q = SymbolQuery.parse("AbstractProAi");
    assertThat(q.bareName()).isEqualTo("AbstractProAi");
    assertThat(q.ownerName()).isNull();
    assertThat(q.memberName()).isNull();
  }

  @Test
  void packageQualifiedTypeNameTakesLastSegment() {
    SymbolQuery q = SymbolQuery.parse("org.triplea.ai.sidecar.exec.PurchaseExecutor");
    assertThat(q.bareName()).isEqualTo("PurchaseExecutor");
    assertThat(q.memberName()).isNull();
  }

  @Test
  void typeDotMemberSplitsOnLowercaseTail() {
    SymbolQuery q = SymbolQuery.parse("PurchaseExecutor.execute");
    assertThat(q.ownerName()).isEqualTo("PurchaseExecutor");
    assertThat(q.memberName()).isEqualTo("execute");
  }

  @Test
  void packageQualifiedTypeDotMemberTakesOwnerLastSegmentOnly() {
    SymbolQuery q = SymbolQuery.parse("org.triplea.ai.sidecar.exec.PurchaseExecutor.execute");
    assertThat(q.ownerName()).isEqualTo("PurchaseExecutor");
    assertThat(q.memberName()).isEqualTo("execute");
  }

  @Test
  void bareMemberNameHasNoOwner() {
    SymbolQuery q = SymbolQuery.parse("execute");
    assertThat(q.ownerName()).isNull();
    assertThat(q.memberName()).isNull(); // no dot at all -> read via bareName, not memberName
    assertThat(q.bareName()).isEqualTo("execute");
  }
}
