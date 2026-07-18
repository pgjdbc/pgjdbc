/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs one differential cell against a connection and captures its {@link ObservableOutcome}. The same
 * method runs against the current driver and the baseline, since both are reached through {@code
 * java.sql.*}. Every failure is caught and folded into the outcome, so a probe never leaks an exception.
 */
public final class DifferentialProbe {
  private DifferentialProbe() {
  }

  /**
   * Read half: read a fixed server value through one accessor. The value comes from a literal cast
   * ({@code SELECT '...'::type}), so no bind is involved and the outcome isolates {@code ResultSet.getX}.
   */
  public static ObservableOutcome read(Connection connection, String selectSql, Accessor accessor) {
    try (Statement st = connection.createStatement();
         ResultSet rs = st.executeQuery(selectSql)) {
      if (!rs.next()) {
        return ObservableOutcome.thrown(new SQLException("query returned no row: " + selectSql));
      }
      Object value;
      try {
        value = accessor.get(rs, 1);
      } catch (Throwable t) {
        return ObservableOutcome.thrown(t);
      }
      boolean wasNull = rs.wasNull();
      return ObservableOutcome.value(wasNull, OutcomeComparator.normalize(value));
    } catch (Throwable t) {
      return ObservableOutcome.thrown(t);
    }
  }

  /**
   * Write half: bind a value through one setter, send it, and read it back as a canonical string. The
   * outcome isolates {@code PreparedStatement.setX}: a send-path encoding change shows up as a differing
   * round-trip value, and a refusal shows up as a thrown outcome.
   */
  public static ObservableOutcome write(Connection connection, Binder binder) {
    try (PreparedStatement ps = connection.prepareStatement("SELECT ?")) {
      try {
        binder.bind(ps, 1);
      } catch (Throwable t) {
        return ObservableOutcome.thrown(t);
      }
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return ObservableOutcome.thrown(new SQLException("query returned no row"));
        }
        String read;
        try {
          read = rs.getString(1);
        } catch (Throwable t) {
          return ObservableOutcome.thrown(t);
        }
        boolean wasNull = rs.wasNull();
        return ObservableOutcome.value(wasNull, read == null ? null : "str:" + read);
      }
    } catch (Throwable t) {
      return ObservableOutcome.thrown(t);
    }
  }

  /**
   * Creates the {@code pg_temp.echo(anyelement)} function used by {@link #readCallable}: a polymorphic
   * identity function whose return type follows its argument, so one function serves every type. Session
   * local ({@code pg_temp}), so it is created once per connection.
   */
  public static void createEchoFunction(Connection connection) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute("CREATE FUNCTION pg_temp.echo(anyelement) RETURNS anyelement "
          + "LANGUAGE sql IMMUTABLE AS 'SELECT $1'");
    }
  }

  /**
   * CallableStatement read half: read a fixed server value back through a function's out parameter
   * ({@code { ? = call pg_temp.echo(<argExpr>) }}) and one CallableStatement getter. Isolates {@code
   * PgCallableStatement.getX}, a separate coercion surface from {@code ResultSet}.
   */
  public static ObservableOutcome readCallable(Connection connection, String argExpr,
      int registerType, CsAccessor accessor) {
    try (CallableStatement cs = connection.prepareCall("{ ? = call pg_temp.echo(" + argExpr + ") }")) {
      cs.registerOutParameter(1, registerType);
      cs.execute();
      Object value;
      try {
        value = accessor.get(cs, 1);
      } catch (Throwable t) {
        return ObservableOutcome.thrown(t);
      }
      boolean wasNull = cs.wasNull();
      return ObservableOutcome.value(wasNull, OutcomeComparator.normalize(value));
    } catch (Throwable t) {
      return ObservableOutcome.thrown(t);
    }
  }

  /**
   * Write half with an arbitrary value: bind {@code value} via {@code setObject} into a specific type
   * ({@code SELECT CAST(? AS <castType>)}) and read it back as a canonical string. The fuzzer feeds
   * generated values here to isolate the send-side encode and its round-trip fidelity for one type.
   */
  public static ObservableOutcome writeValue(Connection connection, String castType,
      @Nullable Object value) {
    try (PreparedStatement ps = connection.prepareStatement("SELECT CAST(? AS " + castType + ")")) {
      try {
        ps.setObject(1, value);
      } catch (Throwable t) {
        return ObservableOutcome.thrown(t);
      }
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return ObservableOutcome.thrown(new SQLException("query returned no row"));
        }
        String read;
        try {
          read = rs.getString(1);
        } catch (Throwable t) {
          return ObservableOutcome.thrown(t);
        }
        boolean wasNull = rs.wasNull();
        return ObservableOutcome.value(wasNull, read == null ? null : "str:" + read);
      }
    } catch (Throwable t) {
      return ObservableOutcome.thrown(t);
    }
  }
}
