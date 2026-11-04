/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * The Bind, Parse and SimpleQuery traces of statements run on a live connection are never longer
 * than {@code maxLogMessageLength}, in extended and in simple query mode.
 */
class MaxLogMessageLengthTest {
  private static final int LIMIT = 200;
  private static final List<String> LIMITED_TRACES =
      Arrays.asList(" FE=> Bind(", " FE=> Parse(", " FE=> SimpleQuery(");

  @ParameterizedTest
  @ValueSource(strings = {"extended", "simple"})
  void tracesOfLargeParametersStayWithinTheLimit(String queryMode) throws SQLException {
    Logger logger = Logger.getLogger("org.postgresql");
    Level previousLevel = logger.getLevel();
    Formatter formatter = new SimpleFormatter();
    List<String> messages = new ArrayList<>();
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        synchronized (messages) {
          messages.add(formatter.formatMessage(record));
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };

    Properties props = new Properties();
    PGProperty.MAX_LOG_MESSAGE_LENGTH.set(props, LIMIT);
    PGProperty.PREFER_QUERY_MODE.set(props, queryMode);
    char[] chars = new char[10_000];
    Arrays.fill(chars, 'x');
    String longText = new String(chars);

    logger.setLevel(Level.FINEST);
    logger.addHandler(handler);
    try (Connection con = TestUtil.openDB(props)) {
      try (PreparedStatement ps = con.prepareStatement("select ?, ?, length(?)")) {
        ps.setString(1, longText);
        ps.setBytes(2, new byte[5_000]);
        ps.setString(3, "select '" + longText + "'");
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(longText, rs.getString(1));
        }
      }
      try (Statement st = con.createStatement();
           ResultSet rs = st.executeQuery("select length('" + longText + "')")) {
        assertTrue(rs.next());
        assertEquals(longText.length(), rs.getInt(1));
      }
    } finally {
      logger.removeHandler(handler);
      logger.setLevel(previousLevel);
    }

    List<String> limited = new ArrayList<>();
    synchronized (messages) {
      for (String message : messages) {
        for (String trace : LIMITED_TRACES) {
          if (message.startsWith(trace)) {
            limited.add(message);
          }
        }
      }
    }
    assertFalse(limited.isEmpty(), "no Bind, Parse or SimpleQuery trace was logged");
    for (String message : limited) {
      assertTrue(message.length() <= LIMIT,
          () -> "trace of " + message.length() + " characters exceeds " + LIMIT + ": " + message);
    }
  }

  @Test
  void aNegativeLimitIsRejected() {
    Properties props = new Properties();
    props.setProperty(PGProperty.MAX_LOG_MESSAGE_LENGTH.getName(), "-1");
    SQLException e = assertThrows(SQLException.class, () -> TestUtil.openDB(props).close());
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState(), e::getMessage);
  }
}
