/**
 * Copyright (c) 2013, impossibl.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  * Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of impossibl.com nor the names of its contributors may
 *    be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package org.postgresql.jdbc;

import org.postgresql.types.Type;

/*
import com.impossibl.postgres.api.data.ACLItem;
import com.impossibl.postgres.api.data.CidrAddr;
import com.impossibl.postgres.api.data.InetAddr;
import com.impossibl.postgres.api.data.Interval;
import com.impossibl.postgres.api.jdbc.PGAnyType;
import com.impossibl.postgres.api.jdbc.PGConnection;
import com.impossibl.postgres.api.jdbc.PGType;
import com.impossibl.postgres.system.JavaTypeMapping;
import com.impossibl.postgres.types.ArrayType;
import com.impossibl.postgres.types.CompositeType;
import com.impossibl.postgres.types.DomainType;
import com.impossibl.postgres.types.RangeType;
import com.impossibl.postgres.types.Registry;

import static com.impossibl.postgres.jdbc.ErrorUtils.makeSQLException;
*/

import org.postgresql.types.Registry;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.BitSet;
import java.util.Map;
import java.util.UUID;

class JDBCTypeMapping {

  public static Type getType(SQLType sqlType, Object value, Registry reg) throws SQLException {

    if (sqlType instanceof JDBCType) {
      return JDBCTypeMapping.getType((JDBCType) sqlType, value, reg);
    }
    else if (sqlType instanceof PGAnyType) {

      PGAnyType pgType = (PGAnyType) sqlType;

      if (pgType.getRequiredVersion() != null) {
        if (!reg.getShared().getServerVersion().isMinimum(pgType.getRequiredVersion())) {
          throw new PGSQLSimpleException("PGType '" + pgType.getName() + "' requires server version " + pgType.getRequiredVersion());
        }
      }

      try {
        if (sqlType.getVendorTypeNumber() != null) {

          return reg.loadType(sqlType.getVendorTypeNumber());
        }
        else {
          return reg.loadStableType(sqlType.getName());
        }
      }
      catch (IOException e) {
        throw new IllegalStateException(e);
        // throw makeSQLException(e);
      }
    }

    throw new PGSQLSimpleException("Unsupported SQLType");
  }

  static SQLType getSpecificSQLType(SQLType sqlType, String typeName, PGConnection connection) throws SQLException {
    if (sqlType instanceof JDBCType) {
      switch ((JDBCType)sqlType) {
        case REF:
        case JAVA_OBJECT:
        case OTHER:
        case STRUCT:
        case DISTINCT:
          sqlType = connection.resolveType(typeName);
      }
    }
    return sqlType;
  }

  static SQLType getSQLType(Object value) {
    JDBCType jdbcType = getJDBCType(value);
    if (jdbcType != JDBCType.OTHER) {
      return jdbcType;
    }
    if (value instanceof Map) {
      return PGType.HSTORE;
    }
    if (value instanceof Interval) {
      return PGType.INTERVAL;
    }
    if (value instanceof UUID) {
      return PGType.UUID;
    }
    if (value instanceof BitSet) {
      return PGType.VARBIT;
    }
    if (value instanceof CidrAddr) {
      return PGType.CIDR;
    }
    if (value instanceof InetAddr) {
      return PGType.INET;
    }
    if (value instanceof ACLItem) {
      return PGType.ACL_ITEM;
    }
    return jdbcType;
  }

  // Don't need this.
  static JDBCType getJDBCType(Object value) {
    if (value == null) {
      return JDBCType.NULL;
    }
    if (value instanceof Boolean) {
      return JDBCType.BOOLEAN;
    }
    if (value instanceof Byte) {
      return JDBCType.TINYINT;
    }
    if (value instanceof Short) {
      return JDBCType.SMALLINT;
    }
    if (value instanceof Integer) {
      return JDBCType.INTEGER;
    }
    if (value instanceof Long) {
      return JDBCType.BIGINT;
    }
    if (value instanceof Float) {
      return JDBCType.REAL;
    }
    if (value instanceof Double) {
      return JDBCType.DOUBLE;
    }
    if (value instanceof BigDecimal || value instanceof BigInteger) {
      return JDBCType.DECIMAL;
    }
    if (value instanceof Number) {
      return JDBCType.NUMERIC;
    }
    if (value instanceof Character) {
      return JDBCType.CHAR;
    }
    if (value instanceof String || value instanceof URL) {
      return JDBCType.VARCHAR;
    }
    if (value instanceof Date || value instanceof LocalDate) {
      return JDBCType.DATE;
    }
    if (value instanceof Time || value instanceof LocalTime) {
      return JDBCType.TIME;
    }
    if (value instanceof OffsetTime) {
      return JDBCType.TIME_WITH_TIMEZONE;
    }
    if (value instanceof Timestamp || value instanceof LocalDateTime) {
      return JDBCType.TIMESTAMP;
    }
    if (value instanceof OffsetDateTime) {
      return JDBCType.TIMESTAMP_WITH_TIMEZONE;
    }
    if (value instanceof byte[]) {
      return JDBCType.VARBINARY;
    }
    if (value instanceof InputStream) {
      return JDBCType.LONGVARBINARY;
    }
    if (value instanceof Reader) {
      return JDBCType.LONGNVARCHAR;
    }
    if (value instanceof Blob) {
      return JDBCType.BLOB;
    }
    if (value instanceof Clob) {
      return JDBCType.CLOB;
    }
    if (value instanceof Array) {
      return JDBCType.ARRAY;
    }
    if (value instanceof Struct) {
      return JDBCType.STRUCT;
    }
    if (value instanceof SQLData) {
      return JDBCType.STRUCT;
    }
    if (value instanceof Ref) {
      return JDBCType.REF;
    }
    if (value instanceof SQLXML) {
      return JDBCType.SQLXML;
    }
    if (value instanceof RowId) {
      return JDBCType.ROWID;
    }
    return JDBCType.OTHER;
  }

  // Don't need this.
  /*
  static JDBCType getJDBCType(Type type) {

    PGType pgType = PGType.valueOf(type);
    if (pgType != null) {
      return pgType.getMappedType();
    }

    if (type instanceof ArrayType) {
      return JDBCType.ARRAY;
    }
    if (type instanceof CompositeType) {
      return JDBCType.STRUCT;
    }
    if (type instanceof DomainType) {
      return JDBCType.DISTINCT;
    }
    if (type instanceof RangeType) {
      return getJDBCType(((RangeType) type).getBase());
    }

    return JDBCType.OTHER;
  }
  */

  // Don't need this.
  /*
  static int getJDBCTypeCode(Type type) {
    return getJDBCType(type).getVendorTypeNumber();
  }
  */

  private static Type getType(JDBCType jdbcType, Object val, Registry reg) throws SQLException {
    switch (jdbcType) {
      case NULL:
        return reg.loadBaseType("text");
      case BIT:
      case BOOLEAN:
        return reg.loadBaseType("bool");
      case TINYINT:
      case SMALLINT:
        return reg.loadBaseType("int2");
      case INTEGER:
        return reg.loadBaseType("int4");
      case BIGINT:
        return reg.loadBaseType("int8");
      case REAL:
        return reg.loadBaseType("float4");
      case FLOAT:
      case DOUBLE:
        return reg.loadBaseType("float8");
      case NUMERIC:
      case DECIMAL:
        return reg.loadBaseType("numeric");
      case CHAR:
        return reg.loadBaseType("char");
      case VARCHAR:
      case LONGVARCHAR:
        return reg.loadBaseType("varchar");
      case DATE:
        return reg.loadBaseType("date");
      case TIME:
        return reg.loadBaseType("time");
      case TIME_WITH_TIMEZONE:
        return reg.loadBaseType("timetz");
      case TIMESTAMP:
        return reg.loadBaseType("timestamp");
      case TIMESTAMP_WITH_TIMEZONE:
        return reg.loadBaseType("timestamptz");
      case BINARY:
      case VARBINARY:
      case LONGVARBINARY:
        return reg.loadBaseType("bytea");
      case BLOB:
      case CLOB:
        return reg.loadBaseType("oid");
      case ARRAY:
        try {
          if (val instanceof PGArray) {
            return ((PGArray) val).getType();
          }
          else if (val instanceof Array) {
            throw new PGSQLSimpleException("Invalid array, not created by this driver");
          }
          else if (val != null) {
            Type elementType;
            if (java.lang.reflect.Array.getLength(val) > 0) {
              Object element = java.lang.reflect.Array.get(val, 0);
              elementType = getType(getJDBCType(element), element, reg);
            }
            else {
              elementType = JavaTypeMapping.getType(val.getClass().getComponentType(), reg);
            }
            if (elementType == null) {
              return null;
            }
            // Now that we have the most accurate element type we
            // can determine, use that to find its actual array type.
            return reg.loadType(elementType.getArrayTypeId());
          }
          return null;
        }
        catch (IOException e) {
          throw makeSQLException(e);
        }
      case ROWID:
        return reg.loadBaseType("tid");
      case SQLXML:
        return reg.loadBaseType("xml");
      case DISTINCT:
        return reg.loadBaseType("domain");
      case STRUCT:
      case JAVA_OBJECT:
      case OTHER:
        try {
          if (val instanceof Struct) {
            return reg.loadTransientType(((Struct) val).getSQLTypeName());
          }
          if (val instanceof SQLData) {
            return reg.loadTransientType(((SQLData) val).getSQLTypeName());
          }
          if (val != null) {
            return JavaTypeMapping.getExtendedType(val.getClass(), reg);
          }
          return null;
        }
        catch (IOException e) {
          throw makeSQLException(e);
        }
      case REF_CURSOR:
        return reg.loadBaseType("refcursor");
      case REF:
      case DATALINK:
      case NCHAR:
      case NVARCHAR:
      case LONGNVARCHAR:
      case NCLOB:
      default:
        return null;
    }
  }
}
