/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.PGProperty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

/**
 * Tests for GitHub issue #3307: with {@code autosave=always} the driver injected a
 * {@code SAVEPOINT PGJDBC_AUTOSAVE} before a {@code SET TRANSACTION ISOLATION LEVEL} statement, so
 * the statement failed with "SET TRANSACTION ISOLATION LEVEL must not be called in a subtransaction".
 *
 * <p>The same server-side restriction applies to the {@code SET LOCAL TRANSACTION ...} and
 * {@code SET SESSION TRANSACTION ...} forms, so none of them must be preceded by an automatic
 * savepoint.</p>
 *
 * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3307">Issue #3307</a>
 */
@ParameterizedClass
@MethodSource("data")
class AutoSaveTransactionSettingsTest extends BaseTest4 {
  AutoSaveTransactionSettingsTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  static Iterable<Arguments> data() {
    Collection<Arguments> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(arguments(binaryMode));
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.AUTOSAVE.set(props, "always");
  }

  @Test
  void setTransactionIsolationLevelAsFirstStatement() throws Exception {
    assertIsolationLevelCanBeSet("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY DEFERRABLE");
  }

  @Test
  void setLocalTransactionIsolationLevelAsFirstStatement() throws Exception {
    assertIsolationLevelCanBeSet("SET LOCAL TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY DEFERRABLE");
  }

  @Test
  void setSessionTransactionIsolationLevelAsFirstStatement() throws Exception {
    assertIsolationLevelCanBeSet("SET SESSION TRANSACTION ISOLATION LEVEL SERIALIZABLE");
  }

  /**
   * Runs {@code sql} as the very first statement of a transaction with {@code autosave=always}. The
   * statement must reach the server without a preceding {@code SAVEPOINT}, otherwise the server
   * rejects it as called in a subtransaction.
   */
  private void assertIsolationLevelCanBeSet(String sql) throws Exception {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      assertDoesNotThrow(() -> stmt.execute(sql),
          () -> "autosave=always must not inject a SAVEPOINT before \"" + sql + "\"");
      assertEquals(Connection.TRANSACTION_SERIALIZABLE, con.getTransactionIsolation(),
          "transaction isolation level should be SERIALIZABLE after \"" + sql + "\"");
    }
    con.commit();
  }
}
