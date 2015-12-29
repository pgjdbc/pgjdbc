/*-------------------------------------------------------------------------
*
* Copyright (c) 2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.jdbc;

import org.postgresql.core.CachedQuery;
import org.postgresql.core.JdbcCallParseInfo;
import org.postgresql.core.Parser;
import org.postgresql.core.Query;
import org.postgresql.util.LruCache;

import java.sql.SQLException;

/**
 * Creates an instance of {@link CachedQuery} for a given connection.
 */
class CachedQueryCreateAction implements LruCache.CreateAction<Object, CachedQuery> {
  private final int serverVersionNum;
  private final PgConnection connection;

  public CachedQueryCreateAction(PgConnection connection, int serverVersionNum) {
    this.connection = connection;
    this.serverVersionNum = serverVersionNum;
  }

  @Override
  public CachedQuery create(Object key) throws SQLException {
    String sql = key == null ? null : key.toString();
    String parsedSql =
        PgStatement.replaceProcessing(sql, true, connection.getStandardConformingStrings());
    boolean isFunction;
    boolean outParmBeforeFunc;
    if (key instanceof CallableQueryKey) {
      JdbcCallParseInfo callInfo =
          Parser.modifyJdbcCall(parsedSql, connection.getStandardConformingStrings(),
              serverVersionNum, connection.getProtocolVersion());
      parsedSql = callInfo.getSql();
      isFunction = callInfo.isFunction();
      outParmBeforeFunc = callInfo.isOutParmBeforeFunc();
    } else {
      isFunction = false;
      outParmBeforeFunc = false;
    }
    Query query = connection.getQueryExecutor().createParameterizedQuery(parsedSql);
    return new CachedQuery(key, query, isFunction, outParmBeforeFunc);
  }
}
