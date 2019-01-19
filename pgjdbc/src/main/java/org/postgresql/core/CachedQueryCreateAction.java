/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.core.Parser.ParserBufferHolder;
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
        : "Query key should be String or BaseQueryKey. Given " + key.getClass() + ", sql: " + key;

    BaseQueryKey queryKey;
    String parsedSql;
    if (key instanceof BaseQueryKey) {
      queryKey = (BaseQueryKey) key;
      parsedSql = queryKey.sql;
    } else {
      queryKey = null;
      parsedSql = (String) key;
    }

    // it's better to have a slightly over sized buffer than to re-allocate it several times
    // here we try to account for escaping, parameter replacement, and procedure call suffix / prefix
    int heuristicBufferSize = Math.max(parsedSql.length() + 35, (int) (parsedSql.length() * 1.15));

    ParserBufferHolder bufferHolder = new ParserBufferHolder(parsedSql);
    bufferHolder.ensureCapacity(heuristicBufferSize);

    if (key instanceof String || queryKey.escapeProcessing) {
      Parser.replaceProcessing(bufferHolder, true, queryExecutor.getStandardConformingStrings());
    }

    boolean isFunction = false;
    if (key instanceof CallableQueryKey) {
      JdbcCallParseInfo callInfo =
          Parser.modifyJdbcCall(bufferHolder, queryExecutor.getStandardConformingStrings(),
              queryExecutor.getServerVersionNum(), queryExecutor.getProtocolVersion());
      isFunction = callInfo.isFunction();
    }

    boolean isParameterized = key instanceof String || queryKey.isParameterized;
    boolean splitStatements = isParameterized || queryExecutor.getPreferQueryMode().compareTo(PreferQueryMode.EXTENDED) >= 0;

    String[] returningColumns;
    if (key instanceof QueryWithReturningColumnsKey) {
      returningColumns = ((QueryWithReturningColumnsKey) key).columnNames;
    } else {
      returningColumns = EMPTY_RETURNING;
    }

    List<NativeQuery> queries = Parser.parseJdbcSql(bufferHolder,
        queryExecutor.getStandardConformingStrings(), isParameterized, splitStatements,
        queryExecutor.isReWriteBatchedInsertsEnabled(), returningColumns);

    Query query = queryExecutor.wrap(queries);
    return new CachedQuery(key, query, isFunction);
  }
}
