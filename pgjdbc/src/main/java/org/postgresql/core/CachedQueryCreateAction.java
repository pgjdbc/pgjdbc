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
              queryExecutor.getServerVersionNum(), queryExecutor.getEscapeSyntaxCallMode());
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

    List<NativeQuery> queries = parse(parsedSql, isParameterized, splitStatements, returningColumns);

    if (returningColumns.length > 0 && queries.size() > 1) {
      // Multi-statement SQL is wrapped in a CompositeQuery, which carries no SqlCommand, so
      // PgConnection and PgStatement ignore generated keys for it. The RETURNING clause added to
      // each statement would then produce rows that nothing reads, and executeUpdate would
      // fail with "A result was returned when none was expected". JDBC allows a driver to ignore
      // the column names when the statement cannot return generated keys, so parse again without
      // them. Modes that do not split statements are handled by the parser itself, which knows
      // the statement count without a second pass.
      queries = parse(parsedSql, isParameterized, splitStatements, EMPTY_RETURNING);
    }

    Query query = queryExecutor.wrap(queries);
    return new CachedQuery(key, query, isFunction);
  }

  private List<NativeQuery> parse(String sql, boolean isParameterized, boolean splitStatements,
      String[] returningColumns) throws SQLException {
    return Parser.parseJdbcSql(sql,
        queryExecutor.getStandardConformingStrings(), isParameterized, splitStatements,
        queryExecutor.isReWriteBatchedInsertsEnabled(), queryExecutor.getQuoteReturningIdentifiers(),
        returningColumns);
  }
}
