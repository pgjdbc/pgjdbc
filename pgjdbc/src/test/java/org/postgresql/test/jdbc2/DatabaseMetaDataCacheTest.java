/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/*
* Tests for caching of DatabaseMetadata
*
*/
class DatabaseMetaDataCacheTest {
  private PgConnection con;
  private TestLogHandler log;
  private Logger driverLogger;
  private Level driverLogLevel;

  private static final Pattern SQL_TYPE_QUERY_LOG_FILTER = Pattern.compile("querying SQL typecode for pg type");
  private static final Pattern SQL_TYPE_CACHE_LOG_FILTER = Pattern.compile("caching all SQL typecodes");

  @BeforeEach
  void setUp() throws Exception {
    con = (PgConnection) TestUtil.openDB();
    log = new TestLogHandler();
    driverLogger = LogManager.getLogManager().getLogger("org.postgresql");
    driverLogger.addHandler(log);
    driverLogLevel = driverLogger.getLevel();
    driverLogger.setLevel(Level.ALL);
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.closeDB(con);
    driverLogger.removeHandler(log);
    driverLogger.setLevel(driverLogLevel);
    log = null;
  }

  @Test
  void getSQLTypeQueryCache() throws SQLException {
    TypeInfo ti = con.getTypeInfo();

    List<LogRecord> typeQueries = log.getRecordsMatching(SQL_TYPE_QUERY_LOG_FILTER);
    assertEquals(0, typeQueries.size());

    ti.getSQLType("xid");  // this must be a type not in the hardcoded 'types' list
    typeQueries = log.getRecordsMatching(SQL_TYPE_QUERY_LOG_FILTER);
    assertEquals(1, typeQueries.size());

    ti.getSQLType("xid");  // this time it should be retrieved from the cache
    typeQueries = log.getRecordsMatching(SQL_TYPE_QUERY_LOG_FILTER);
    assertEquals(1, typeQueries.size());
  }

  @Test
  void getTypeInfoUsesCache() throws SQLException {
    con.getMetaData().getTypeInfo();

    List<LogRecord> typeCacheQuery = log.getRecordsMatching(SQL_TYPE_CACHE_LOG_FILTER);
    assertEquals(1, typeCacheQuery.size(), "PgDatabaseMetadata.getTypeInfo() did not cache SQL typecodes");

    List<LogRecord> typeQueries = log.getRecordsMatching(SQL_TYPE_QUERY_LOG_FILTER);
    assertEquals(0, typeQueries.size(), "PgDatabaseMetadata.getTypeInfo() resulted in individual queries for SQL typecodes");
  }

  @Test
  void typeForAlias() {
    TypeInfo ti = con.getTypeInfo();
    assertEquals("bool", ti.getTypeForAlias("boolean"));
    assertEquals("bool", ti.getTypeForAlias("Boolean"));
    assertEquals("bool", ti.getTypeForAlias("Bool"));
    assertEquals("bogus", ti.getTypeForAlias("bogus"));
  }
}
