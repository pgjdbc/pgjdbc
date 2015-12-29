/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core;

/**
 * Represents a query that is ready for execution by backend. The main difference from JDBC is ? are
 * replaced with $1, $2, etc.
 */
public class NativeQuery {
  private final static String[] BIND_NAMES = new String[128];
  private final static int[] NO_BINDS = new int[0];

  public final String nativeSql;
  public final int[] bindPositions;

  static {
    for (int i = 1; i < BIND_NAMES.length; i++) {
      BIND_NAMES[i] = "$" + i;
    }
  }

  public NativeQuery(String nativeSql) {
    this(nativeSql, NO_BINDS);
  }

  public NativeQuery(String nativeSql, int[] bindPositions) {
    this.nativeSql = nativeSql;
    this.bindPositions =
        bindPositions == null || bindPositions.length == 0 ? NO_BINDS : bindPositions;
  }

  /**
   * Stringize this query to a human-readable form, substituting particular parameter values for
   * parameter placeholders.
   *
   * @param parameters a ParameterList returned by this Query's {@link Query#createParameterList}
   *                   method, or <code>null</code> to leave the parameter placeholders
   *                   unsubstituted.
   * @return a human-readable representation of this query
   */
  public String toString(ParameterList parameters) {
    if (bindPositions.length == 0) {
      return nativeSql;
    }

    int queryLength = nativeSql.length();
    String[] params = new String[bindPositions.length];
    for (int i = 1; i <= bindPositions.length; ++i) {
      String param = parameters == null ? "?" : parameters.toString(i);
      params[i - 1] = param;
      queryLength += param.length() - bindName(i).length();
    }

    StringBuilder sbuf = new StringBuilder(queryLength);
    sbuf.append(nativeSql, 0, bindPositions[0]);
    for (int i = 1; i <= bindPositions.length; ++i) {
      sbuf.append(params[i - 1]);
      int nextBind = i < bindPositions.length ? bindPositions[i] : nativeSql.length();
      sbuf.append(nativeSql, bindPositions[i - 1] + bindName(i).length(), nextBind);
    }
    return sbuf.toString();
  }

  /**
   * Returns $1, $2, etc names of bind variables used by backend.
   *
   * @param index index of a bind variable
   * @return bind variable name
   */
  public static String bindName(int index) {
    return index < BIND_NAMES.length ? BIND_NAMES[index] : "$" + index;
  }
}
