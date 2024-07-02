/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGpoint;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * The motivation behind this class is to deal with:
 * <ul>
 * <li>autoboxing, the type of the object is not always the same as the type of the column in the database.
 * Ex: Object a = 1; // a is an Integer, but the oid might be INT8</li>
 * <li>string to internal pg object representation, check the oid if a string literal can be used to create an internal object like PGBox, PGPoint etc,
 * since users don't have access to those types.</li>
 * </ul>
 */
public class JavaObjectResolver {

  private JavaObjectResolver() {
  }

  /**
   * Try to resolve the object to the correct type based on the oid, otherwise return the object as is.
   *
   * @param in             the object to resolve
   * @param oid            the oid of the column
   * @return the resolved object
   */
  public static Object tryResolveObject(final Object in, final int oid) {
    switch (oid) {
      case Oid.INT2:
        return tryResolveObjectToShort(in);
      case Oid.INT4:
        return tryResolveObjectToInt(in);
      case Oid.OID:
      case Oid.INT8:
        return tryResolveObjectToLong(in);
      case Oid.FLOAT4:
        return tryResolveObjectToFloat(in);
      case Oid.FLOAT8:
        return tryResolveObjectToDouble(in);
      case Oid.NUMERIC:
        return tryToResolveObjectToBigDecimal(in);
      case Oid.BOOL:
      case Oid.BIT:
        // this one is related to how PgResultSet converts a bit to a boolean
        return tryResolveObjectToBoolean(in);
      case Oid.CHAR:
      case Oid.BPCHAR:
        return tryResolveObjectToChar(in);
      case Oid.TEXT:
        return tryResolveObjectToString(in);
      case Oid.POINT:
        return tryToResolveObjectToPGPoint(in);
      case Oid.BOX:
        return tryToResolveObjectToPGBox(in);
      case Oid.VARBIT:
      case Oid.JSON:
        return tryToResolveObjectToPGObject(in, oid);
      default:
        return in;
    }
  }

  private static Object tryResolveObjectToShort(final Object in) {
    Class<?> cls = in.getClass();
    if (cls == Integer.class) {
      return (short) (int) in;
    } else if (cls == Long.class) {
      return (short) (long) in;
    }
    return in;
  }

  private static Object tryResolveObjectToInt(final Object in) {
    if (in instanceof Long) {
      return (int) (long) in;
    }
    return in;
  }

  private static Object tryResolveObjectToLong(final Object in) {
    if (in instanceof Integer) {
      return (long) (int) in;
    }
    return in;
  }

  private static Object tryResolveObjectToFloat(final Object in) {
    if (in instanceof Double) {
      return (float) (double) in;
    } else if (in instanceof Integer) {
      return (float) (int) in;
    } else if (in instanceof Long) {
      return (float) (long) in;
    }
    return in;
  }

  private static Object tryResolveObjectToDouble(final Object in) {
    if (in instanceof Float) {
      return (double) (float) in;
    } else if (in instanceof Integer) {
      return (double) (int) in;
    } else if (in instanceof Long) {
      return (double) (long) in;
    }
    return in;
  }

  private static Object tryToResolveObjectToBigDecimal(final Object in) {
    if (in instanceof Integer) {
      return BigDecimal.valueOf((int) in);
    } else if (in instanceof Float) {
      return BigDecimal.valueOf((float) in);
    } else if (in instanceof Double) {
      return BigDecimal.valueOf((double) in);
    }
    return in;
  }

  private static Object tryResolveObjectToBoolean(final Object in) {
    try {
      return BooleanTypeUtil.castToBoolean(in);
    } catch (PSQLException e) {
      return in;
    }
  }

  private static Object tryResolveObjectToChar(final Object in) {
    if (in instanceof String) {
      String str = (String) in;
      if (str.length() == 1) {
        return str.charAt(0);
      }
      return in;
    }
    return in;
  }

  private static Object tryResolveObjectToString(Object in) {
    String s = in.toString();
    if (s.length() == 1) {
      return s.charAt(0);
    }
    return s;
  }

  private static Object tryToResolveObjectToPGPoint(final Object in) {
    if (in instanceof String) {
      try {
        return new PGpoint((String) in);
      } catch (SQLException ignored) { }
    }
    return in;
  }

  private static Object tryToResolveObjectToPGBox(final Object in) {
    if (in instanceof String) {
      try {
        return new PGbox((String) in);
      } catch (SQLException ignored) { }
    }
    return in;
  }

  private static Object tryToResolveObjectToPGObject(final Object in, final int oid) {
    String type = "unknown";
    if (oid == Oid.JSON) {
      type = "json";
    }
    if (in instanceof String || in instanceof Character) {
      PGobject obj = new PGobject();
      obj.setType(type);
      try {
        obj.setValue(String.valueOf(in));
        return obj;
      } catch (SQLException ignored) { }
    }
    return in;
  }
}
