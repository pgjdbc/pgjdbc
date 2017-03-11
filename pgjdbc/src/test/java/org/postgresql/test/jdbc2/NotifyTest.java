/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.PGNotification;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

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
    connectAndNotify("mynotification");

    // Wait a bit to let the notify come through... Changed this so the test takes ~2 seconds
    // less to run and is still as effective.
    PGNotification notifications[] = null;
    try {
      int retries = 20;
      while( retries --> 0 && // Special arrow operator decrements until retries is zero.
    		 (notifications = ((org.postgresql.PGConnection) conn).getNotifications()) == null ) {
          Thread.sleep(100);
      }
    } catch (InterruptedException ie) {
    }

    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  /**
   * To test timeouts we have to send the notification from another thread, because we
   * listener is blocking.
   */
  @Test(timeout=60000) // 60 seconds should be enough
  public void testAsyncNotifyWithTimeout() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Here we let the getNotifications() timeout.
    long startMillis = System.currentTimeMillis();
    PGNotification notifications[] = ((org.postgresql.PGConnection) conn).getNotifications(500);
    long endMillis = System.currentTimeMillis();
    long runtime = endMillis - startMillis;
    assertNull("There have been notifications, althought none have been expected.",notifications);
    Assert.assertTrue("We didn't wait long enough! runtime="+runtime, runtime > 450);

    // Now we check the case where notifications are already available while we are starting to
    // listen for notifications
    connectAndNotify("mynotification");

    notifications = ((org.postgresql.PGConnection) conn).getNotifications(10000);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    // Now we check the case where notifications are already available while we are waiting forever
    connectAndNotify("mynotification");

    notifications = ((org.postgresql.PGConnection) conn).getNotifications(0);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    // Now we check the case where notifications are send after we have started to listen for
    // notifications
    new Thread(()-> {
      try {
   	    Thread.sleep(200);
   	  } catch (InterruptedException ie) {
   	  }
      connectAndNotify("mynotification");
    }).start();

    notifications = ((org.postgresql.PGConnection) conn).getNotifications(10000);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    // Now we check the case where notifications are send after we have started to listen for
    // notifications forever
    new Thread(()-> {
      try {
   	    Thread.sleep(200);
   	  } catch (InterruptedException ie) {
   	  }
      connectAndNotify("mynotification");
    }).start();

    notifications = ((org.postgresql.PGConnection) conn).getNotifications(0);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  private static void connectAndNotify(String channel) {
	Connection conn2 = null;
	try {
      conn2 = TestUtil.openDB();
      Statement stmt2 = conn2.createStatement();
      stmt2.executeUpdate("NOTIFY "+channel);
      stmt2.close();
    } catch (Exception e) {
      throw new RuntimeException("Couldn't notify '"+channel+"'.",e);
	} finally {
      try {
		conn2.close();
	  } catch (SQLException e) {
	  }
    }
  }

}
