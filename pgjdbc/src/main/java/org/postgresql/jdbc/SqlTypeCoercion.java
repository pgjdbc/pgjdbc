/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.sql.SQLException;
import java.sql.Types;

/**
 * Chooses the value coercion a JDBC target type ({@link java.sql.Types}) requires, so the parameter
 * path ({@link PgPreparedStatement#setObject(int, Object, int, int)}) and the composite-attribute
 * path ({@link PgSQLOutput#writeObject(Object, java.sql.SQLType)}) normalise an arbitrary Java value
 * the same way before a codec encodes it.
 *
 * <p>The per-primitive rules live in {@link TypeCoercion}; this class only decides which rule a
 * target type calls for and returns the boxed result. Target types whose codec already accepts the
 * incoming class — temporal, arrays, composites, {@code OTHER}, and the rest — are returned
 * unchanged so the codec can apply its own, wider input handling.</p>
 */
final class SqlTypeCoercion {

  private SqlTypeCoercion() {
  }

  /**
   * Coerces {@code in} to the canonical Java value implied by {@code targetSqlType}.
   *
   * @param in the value to coerce (never null)
   * @param targetSqlType the JDBC {@link java.sql.Types} target
   * @param scaleOrLength the scale for {@code NUMERIC}/{@code DECIMAL}, or -1 for none
   * @return the coerced value, never null
   * @throws SQLException if {@code in} cannot be coerced to {@code targetSqlType}
   */
  static Object coerce(Object in, int targetSqlType, int scaleOrLength) throws SQLException {
    switch (targetSqlType) {
      case Types.TINYINT:
      case Types.SMALLINT:
        return TypeCoercion.toShort(in);
      case Types.INTEGER:
        return TypeCoercion.toInt(in);
      case Types.BIGINT:
        return TypeCoercion.toLong(in);
      case Types.REAL:
        return TypeCoercion.toFloat(in);
      case Types.DOUBLE:
      case Types.FLOAT:
        return TypeCoercion.toDouble(in);
      case Types.NUMERIC:
      case Types.DECIMAL:
        return toNumeric(in, scaleOrLength);
      case Types.CHAR:
      case Types.VARCHAR:
      case Types.LONGVARCHAR:
        return TypeCoercion.toString(in);
      case Types.BOOLEAN:
      case Types.BIT:
        return TypeCoercion.toBoolean(in);
      default:
        return in;
    }
  }

  /**
   * Coerces {@code in} for a {@code numeric}/{@code decimal} target.
   *
   * <p>PostgreSQL {@code numeric} carries {@code NaN} and {@code ±Infinity} (the latter since v14),
   * which {@link java.math.BigDecimal} cannot represent. A non-finite {@code Double}/{@code Float} is
   * therefore handed through unchanged so the numeric codec spells out the sentinel, instead of
   * forcing {@code BigDecimal} — {@code BigDecimal.valueOf(NaN)} throws. Finite values keep the full
   * {@link TypeCoercion#toBigDecimal} handling and honour the requested scale.</p>
   */
  private static Object toNumeric(Object in, int scale) throws SQLException {
    if (in instanceof Double || in instanceof Float) {
      double d = ((Number) in).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return in;
      }
    }
    return TypeCoercion.toBigDecimal(in, scale);
  }
}
