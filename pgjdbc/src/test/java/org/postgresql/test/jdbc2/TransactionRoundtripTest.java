/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

/**
 * Tests the number of network operations required to start and use a transaction.
 */
@ParameterizedClass
@MethodSource("data")
public class TransactionRoundtripTest extends BaseTest4 {
  private CountingSocketFactory.Counters socketCounters = CountingSocketFactory.register();
  private final AutoSave autosave;
  private final boolean cleanupSavepoints;

  public TransactionRoundtripTest(PreferQueryMode preferQueryMode, AutoSave autosave,
      boolean cleanupSavepoints) {
    setPreferQueryMode(preferQueryMode);
    this.autosave = autosave;
    this.cleanupSavepoints = cleanupSavepoints;
  }

  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {PreferQueryMode.EXTENDED, AutoSave.NEVER, false},
        {PreferQueryMode.EXTENDED, AutoSave.ALWAYS, false},
        {PreferQueryMode.EXTENDED, AutoSave.ALWAYS, true},
        {PreferQueryMode.SIMPLE, AutoSave.NEVER, false},
        {PreferQueryMode.SIMPLE, AutoSave.ALWAYS, false},
        {PreferQueryMode.SIMPLE, AutoSave.ALWAYS, true}
    });
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.AUTOSAVE.set(props, autosave.value());
    PGProperty.CLEANUP_SAVEPOINTS.set(props, cleanupSavepoints);
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, socketCounters.key());
  }

  @Override
  protected void tearDown() throws SQLException {
    try {
      super.tearDown();
    } finally {
      CountingSocketFactory.unregister(socketCounters);
    }
  }

  @Test
  void beginAndQueryAreFlushedTogether() throws SQLException {
    con.setAutoCommit(false);
    long flushesBefore = socketCounters.flushes.get();
    long roundtripsBefore = socketCounters.roundtrips.get();

    try (Statement statement = con.createStatement()) {
      statement.executeQuery("SELECT 1").close();
    }

    int expectedFlushes = cleanupSavepoints ? 2 : 1;
    assertEquals(expectedFlushes, socketCounters.flushes.get() - flushesBefore,
        "BEGIN and the query should share one flush; savepoint cleanup needs another");
    assertEquals(1, socketCounters.roundtrips.get() - roundtripsBefore,
        "BEGIN and the query should complete in one roundtrip");
  }
}
