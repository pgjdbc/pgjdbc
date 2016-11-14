/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.postgresql.PGNotification;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class NotifyTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(conn);
  }

  @Test
  public void testNotify() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");
    stmt.executeUpdate("NOTIFY mynotification");

    PGNotification notifications[] = ((org.postgresql.PGConnection) conn).getNotifications();
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  @Test
  public void testNotifyArgument() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_0) || TestUtil.isProtocolVersion(conn, 2)) {
      return;
    }

    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");
    stmt.executeUpdate("NOTIFY mynotification, 'message'");

    PGNotification notifications[] = ((org.postgresql.PGConnection) conn).getNotifications();
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("message", notifications[0].getParameter());

    stmt.close();
  }

  @Test
  public void testAsyncNotify() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Notify on a separate connection to get an async notify on the first.
    Connection conn2 = TestUtil.openDB();
    try {
      Statement stmt2 = conn2.createStatement();
      stmt2.executeUpdate("NOTIFY mynotification");
      stmt2.close();
    } finally {
      conn2.close();
    }

    // Wait a bit to let the notify come through..
    try {
      Thread.sleep(2000);
    } catch (InterruptedException ie) {
    }

    PGNotification notifications[] = ((org.postgresql.PGConnection) conn).getNotifications();
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }
}
