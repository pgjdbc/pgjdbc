/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.codec.Codec;
import org.postgresql.core.BaseConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.index.qual.Positive;

import java.sql.ParameterMetaData;
import java.sql.SQLException;

public class PgParameterMetaData implements ParameterMetaData {

  private final BaseConnection connection;
  private final int[] oids;

  public PgParameterMetaData(BaseConnection connection, int[] oids) {
    this.connection = connection;
    this.oids = oids;
  }

  @Override
  public String getParameterClassName(@Positive int param) throws SQLException {
    checkParamIndex(param);
    int oid = oids[param - 1];
    PgCodecContext ctx = connection.getCodecContext();
    // Resolve PgType so codec lookup can fall through to typename / typtype
    // resolution rather than returning FallbackCodec.
    PgType pgType = connection.getTypeInfo().getPgTypeByOid(oid);
    Codec codec = ctx.getCodecs().getByOid(oid, pgType);
    if (codec != null) {
      return codec.getDefaultJavaType().getName();
    }
    // Fallback for types without a registered codec
    @SuppressWarnings("deprecation")
    String className = connection.getTypeInfo().getJavaClass(oid);
    return className;
  }

  @Override
  public int getParameterCount() {
    return oids.length;
  }

  /**
   * {@inheritDoc} For now report all parameters as inputs. CallableStatements may have one output,
   * but ignore that for now.
   */
  @Override
  public int getParameterMode(int param) throws SQLException {
    checkParamIndex(param);
    return ParameterMetaData.parameterModeIn;
  }

  @Override
  public int getParameterType(int param) throws SQLException {
    checkParamIndex(param);
    return connection.getTypeInfo().getPgTypeByOid(oids[param - 1]).getSqlType();
  }

  @Override
  public String getParameterTypeName(int param) throws SQLException {
    checkParamIndex(param);
    // Return the raw pg_type.typname (e.g. "timestamp", "_int4") rather than
    // format_type()'s pretty name ("timestamp without time zone", "integer[]"),
    // matching the legacy contract that callers rely on.
    return connection.getTypeInfo().getPgTypeByOid(oids[param - 1]).getTypeName().getName();
  }

  // we don't know this
  @Override
  public int getPrecision(int param) throws SQLException {
    checkParamIndex(param);
    return 0;
  }

  // we don't know this
  @Override
  public int getScale(int param) throws SQLException {
    checkParamIndex(param);
    return 0;
  }

  // we can't tell anything about nullability
  @Override
  public int isNullable(int param) throws SQLException {
    checkParamIndex(param);
    return ParameterMetaData.parameterNullableUnknown;
  }

  /**
   * {@inheritDoc} PostgreSQL doesn't have unsigned numbers
   */
  @Override
  public boolean isSigned(int param) throws SQLException {
    checkParamIndex(param);
    return PgType.isSigned(oids[param - 1]);
  }

  private void checkParamIndex(int param) throws PSQLException {
    if (param < 1 || param > oids.length) {
      throw new PSQLException(
          GT.tr("The parameter index is out of range: {0}, number of parameters: {1}.",
              param, oids.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }
}
