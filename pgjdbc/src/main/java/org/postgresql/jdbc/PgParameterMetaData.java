/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.udt.UdtMap;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.CallableStatement;
import java.sql.ParameterMetaData;
import java.sql.SQLException;

public class PgParameterMetaData implements ParameterMetaData {

  private final BaseConnection _connection;
  private final int[] _oids;
  private final String[] _pgTypes;
  private final int[] _scales;

  public PgParameterMetaData(BaseConnection connection, int[] oids, String[] pgTypes, int[] scales) {
    _connection = connection;
    _oids = oids;
    _pgTypes = pgTypes;
    _scales = scales;
  }

  @Override
  public String getParameterClassName(int param) throws SQLException {
    checkParamIndex(param);
    String pgType = _pgTypes[param - 1];
    if (pgType != null) {
      // TODO: Will this always be the udtMap from _connection?
      UdtMap udtMap = _connection.getUdtMap();
      Class<?> customType = udtMap.getTypeMap().get(pgType);
      if (customType != null) {
        return customType.getName();
      }
    }
    return _connection.getTypeInfo().getJavaClass(_oids[param - 1]);
  }

  public int getParameterCount() {
    return _oids.length;
  }

  /**
   * {@inheritDoc} For now report all parameters as inputs. CallableStatements may have one output,
   * but ignore that for now.
   */
  public int getParameterMode(int param) throws SQLException {
    checkParamIndex(param);
    return ParameterMetaData.parameterModeIn;
  }

  public int getParameterType(int param) throws SQLException {
    checkParamIndex(param);
    return _connection.getTypeInfo().getSQLType(_oids[param - 1]);
  }

  @Override
  public String getParameterTypeName(int param) throws SQLException {
    checkParamIndex(param);
    // TODO: udtMap do anything here?
    String pgType = _pgTypes[param - 1];
    return (pgType != null) ? pgType : _connection.getTypeInfo().getPGType(_oids[param - 1]);
  }

  // we don't know this
  public int getPrecision(int param) throws SQLException {
    checkParamIndex(param);
    return 0;
  }

  /**
   * {@inheritDoc}
   *
   * @see CallableStatement#registerOutParameter(int, int, int)
   * @see PgCallableStatement#registerOutParameterImpl(int, java.lang.String, int, int)
   */
  @Override
  public int getScale(int param) throws SQLException {
    checkParamIndex(param);
    int scale = _scales[param - 1];
    return scale >= 0 ? scale : 0;
  }

  // we can't tell anything about nullability
  public int isNullable(int param) throws SQLException {
    checkParamIndex(param);
    return ParameterMetaData.parameterNullableUnknown;
  }

  /**
   * {@inheritDoc} PostgreSQL doesn't have unsigned numbers
   */
  public boolean isSigned(int param) throws SQLException {
    checkParamIndex(param);
    return _connection.getTypeInfo().isSigned(_oids[param - 1]);
  }

  private void checkParamIndex(int param) throws PSQLException {
    if (param < 1 || param > _oids.length) {
      throw new PSQLException(
          GT.tr("The parameter index is out of range: {0}, number of parameters: {1}.",
              param, _oids.length),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isAssignableFrom(getClass());
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }
}
