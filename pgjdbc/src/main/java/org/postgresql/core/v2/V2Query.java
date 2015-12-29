/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core.v2;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Parser;
import org.postgresql.core.ProtocolConnection;
import org.postgresql.core.Query;

import java.util.List;

/**
 * Query implementation for all queries via the V2 protocol.
 */
class V2Query implements Query {
  V2Query(String query, boolean withParameters, ProtocolConnection pconn) {

    useEStringSyntax = pconn.getServerVersionNum() >= 80100;
    boolean stdStrings = pconn.getStandardConformingStrings();

    List<NativeQuery> queries = Parser.parseJdbcSql(query, stdStrings, withParameters, false);
    assert queries.size() <= 1 : "Exactly one query expected in V2. " + queries.size()
        + " queries given.";

    nativeQuery = queries.isEmpty() ? new NativeQuery("") : queries.get(0);
  }

  public ParameterList createParameterList() {
    if (nativeQuery.bindPositions.length == 0) {
      return NO_PARAMETERS;
    }

    return new SimpleParameterList(nativeQuery.bindPositions.length, useEStringSyntax);
  }

  public String toString(ParameterList parameters) {
    return nativeQuery.toString(parameters);
  }

  public void close() {
  }

  NativeQuery getNativeQuery() {
    return nativeQuery;
  }

  public boolean isStatementDescribed() {
    return false;
  }

  public boolean isEmpty() {
    return nativeQuery.nativeSql.isEmpty();
  }

  private static final ParameterList NO_PARAMETERS = new SimpleParameterList(0, false);

  private final NativeQuery nativeQuery;

  private final boolean useEStringSyntax; // whether escaped string syntax should be used
}

