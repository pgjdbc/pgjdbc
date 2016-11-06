/*
 * Copyright (c) 2010, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class AbortTest {

  private static final int SLEEP_SECONDS = 30;
  private static final int SLEEP_MILLISECONDS = SLEEP_SECONDS * 1000;

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(_conn);
  }

  @Test
  public void testAbort() throws SQLException, InterruptedException, ExecutionException {
    final ExecutorService executor = Executors.newFixedThreadPool(2);
    long startTime = System.currentTimeMillis();
    Future<SQLException> workerFuture = executor.submit(new Callable<SQLException>() {
      public SQLException call() {
        try {
          Statement stmt = _conn.createStatement();
          stmt.execute("SELECT pg_sleep(" + SLEEP_SECONDS + ")");
        } catch (SQLException e) {
          return e;
        }
        return null;
      }
    });
    Future<SQLException> abortFuture = executor.submit(new Callable<SQLException>() {
      public SQLException call() {
        ExecutorService abortExecutor = Executors.newSingleThreadExecutor();
        try {
          _conn.abort(abortExecutor);
        } catch (SQLException e) {
          return e;
        }
        abortExecutor.shutdown();
        try {
          abortExecutor.awaitTermination(SLEEP_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        return null;
      }
    });
    SQLException workerException = workerFuture.get();
    long endTime = System.currentTimeMillis();
    SQLException abortException = abortFuture.get();
    if (abortException != null) {
      throw abortException;
    }
    if (workerException == null) {
      fail("Statement execution should have been aborted, thus throwing an exception");
    }
    // suppose that if it took at least 95% of sleep time, aborting has failed and we've waited the
    // full time
    assertTrue(endTime - startTime < SLEEP_MILLISECONDS * 95 / 100);
    assertTrue(_conn.isClosed());
  }

  /**
   * According to the javadoc, calling abort on a closed connection is a no-op.
   */
  @Test
  public void testAbortOnClosedConnection() throws SQLException {
    _conn.close();
    try {
      _conn.abort(Executors.newSingleThreadExecutor());
    } catch (SQLException e) {
      fail(e.getMessage());
    }
  }
}
