/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * The write-half axis: one {@link PreparedStatement} setter each, with a fixed representative value. The
 * differential oracle binds the value through both drivers, sends it to the server, and reads it back
 * canonically, so an encoding change on the send path surfaces as a differing round-trip value.
 *
 * <p>Inputs are JDK types only ({@code Integer}, {@code String}, {@code byte[]}, ...): they share the
 * bootstrap class loader, so the same instance feeds both drivers. Binding a driver-specific type (for
 * example {@code PGobject}) would need one instance per loader and is out of scope for now.
 */
public enum Binder {
  SET_INT {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setInt(index, 2147483647);
    }
  },
  SET_LONG {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setLong(index, 9223372036854775807L);
    }
  },
  SET_STRING {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setString(index, "hello");
    }
  },
  SET_BIG_DECIMAL {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setBigDecimal(index, new BigDecimal("12345.6700"));
    }
  },
  SET_BYTES {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setBytes(index, new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef});
    }
  },
  SET_BOOLEAN {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setBoolean(index, true);
    }
  },
  SET_OBJECT {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setObject(index, 42);
    }
  },
  SET_OBJECT_NUMERIC_SQLTYPE {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setObject(index, new BigDecimal("12345.6700"), Types.NUMERIC);
    }
  },
  SET_DATE {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setDate(index, Date.valueOf("2020-01-02"));
    }
  },
  SET_TIME {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setTime(index, Time.valueOf("12:34:56"));
    }
  },
  SET_TIMESTAMP {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setTimestamp(index, Timestamp.valueOf("2020-01-02 12:34:56"));
    }
  },
  SET_NULL {
    @Override
    void bind(PreparedStatement ps, int index) throws SQLException {
      ps.setNull(index, Types.VARCHAR);
    }
  };

  abstract void bind(PreparedStatement ps, int index) throws SQLException;
}
