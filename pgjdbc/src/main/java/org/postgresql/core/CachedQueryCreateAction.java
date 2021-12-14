/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.util.LruCache;

import java.sql.SQLException;
import java.util.List;

/**
 * Creates an instance of {@link CachedQuery} for a given connection.
 */
class CachedQueryCreateAction implements LruCache.CreateAction<Object, CachedQuery> {
  private static final String[] EMPTY_RETURNING = new String[0];
  private final QueryExecutor queryExecutor;

  CachedQueryCreateAction(QueryExecutor queryExecutor) {
    this.queryExecutor = queryExecutor;
  }

  @Override
  public CachedQuery create(Object key) throws SQLException {
    assert key instanceof String || key instanceof BaseQueryKey
        : "Query key should be String or BaseQueryKey. Given " + key.getClass() + ", sql: "
        + key;
    BaseQueryKey queryKey;
    String parsedSql;
    if (key instanceof BaseQueryKey) {
      queryKey = (BaseQueryKey) key;
      parsedSql = queryKey.sql;
    } else {
      queryKey = null;
      parsedSql = (String) key;
    }
    if (key instanceof String || castNonNull(queryKey).escapeProcessing) {
      parsedSql =
          Parser.replaceProcessing(parsedSql, true, queryExecutor.getStandardConformingStrings());
    }
    boolean isFunction;
    if (key instanceof CallableQueryKey) {
      JdbcCallParseInfo callInfo =
          Parser.modifyJdbcCall(parsedSql, queryExecutor.getStandardConformingStrings(),
              queryExecutor.getServerVersionNum(), queryExecutor.getProtocolVersion(), queryExecutor.getEscapeSyntaxCallMode());
      parsedSql = callInfo.getSql();
      isFunction = callInfo.isFunction();
    } else {
      isFunction = false;
    }
    boolean isParameterized = key instanceof String || castNonNull(queryKey).isParameterized;
    boolean splitStatements = isParameterized || queryExecutor.getPreferQueryMode().compareTo(PreferQueryMode.EXTENDED) >= 0;

    String[] returningColumns;
    if (key instanceof QueryWithReturningColumnsKey) {
      returningColumns = ((QueryWithReturningColumnsKey) key).columnNames;
    } else {
      returningColumns = EMPTY_RETURNING;
    }

    List<NativeQuery> queries = Parser.parseJdbcSql(parsedSql,
        queryExecutor.getStandardConformingStrings(), isParameterized, splitStatements,
        queryExecutor.isReWriteBatchedInsertsEnabled(), queryExecutor.getQuoteReturningIdentifiers(),
        returningColumns
        );

    Query query = queryExecutor.wrap(queries);
    return new CachedQuery(key, query, isFunction);
  }
}
