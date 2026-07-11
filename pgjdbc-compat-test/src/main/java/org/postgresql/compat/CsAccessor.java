/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.CallableStatement;
import java.sql.SQLException;

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
  };

  abstract @Nullable Object get(CallableStatement cs, int index) throws SQLException;
}
