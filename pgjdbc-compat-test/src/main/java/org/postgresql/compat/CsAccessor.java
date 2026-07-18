/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * The CallableStatement read axis: one {@link CallableStatement} out-parameter getter each. It mirrors
 * {@link Accessor} but drives {@code PgCallableStatement}, a distinct public surface with its own coercion
 * code, reached through a function's return value.
 */
public enum CsAccessor {
  CS_GET_STRING {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getString(index);
    }
  },
  CS_GET_BOOLEAN {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getBoolean(index);
    }
  },
  CS_GET_INT {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getInt(index);
    }
  },
  CS_GET_LONG {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getLong(index);
    }
  },
  CS_GET_BIG_DECIMAL {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getBigDecimal(index);
    }
  },
  CS_GET_BYTES {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getBytes(index);
    }
  },
  CS_GET_DATE {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getDate(index);
    }
  },
  CS_GET_TIME {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getTime(index);
    }
  },
  CS_GET_TIMESTAMP {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getTimestamp(index);
    }
  },
  CS_GET_OBJECT {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getObject(index);
    }
  },
  CS_GET_DATE_CAL {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getDate(index, calendar());
    }
  },
  CS_GET_TIME_CAL {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getTime(index, calendar());
    }
  },
  CS_GET_TIMESTAMP_CAL {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getTimestamp(index, calendar());
    }
  },
  CS_GET_OBJECT_LOCAL_TIME {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getObject(index, LocalTime.class);
    }
  },
  CS_GET_OBJECT_OFFSET_TIME {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getObject(index, OffsetTime.class);
    }
  },
  CS_GET_OBJECT_LOCAL_DATE_TIME {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getObject(index, LocalDateTime.class);
    }
  },
  CS_GET_OBJECT_OFFSET_DATE_TIME {
    @Override
    @Nullable Object get(CallableStatement cs, int index) throws SQLException {
      return cs.getObject(index, OffsetDateTime.class);
    }
  };

  /**
   * A calendar in a fixed non-UTC, non-JVM-default zone. The temporal-with-calendar getters must
   * honour it, so a fresh instance is handed out per call: the JDBC drivers may mutate a calendar
   * passed to a getter, and the current and baseline drivers must not share one.
   */
  private static GregorianCalendar calendar() {
    return new GregorianCalendar(TimeZone.getTimeZone("GMT+05:00"));
  }

  abstract @Nullable Object get(CallableStatement cs, int index) throws SQLException;
}
