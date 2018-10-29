/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
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

  @Test(timeout = 60000)
  public void testNotify() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");
    stmt.executeUpdate("NOTIFY mynotification");

    PGNotification[] notifications = conn.unwrap(PGConnection.class).getNotifications();
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  @Test(timeout = 60000)
  public void testNotifyArgument() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_0)) {
      return;
    }

    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");
    stmt.executeUpdate("NOTIFY mynotification, 'message'");

    PGNotification[] notifications = conn.unwrap(PGConnection.class).getNotifications();
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("message", notifications[0].getParameter());

    stmt.close();
  }

  @Test(timeout = 60000)
  public void testAsyncNotify() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Notify on a separate connection to get an async notify on the first.
    connectAndNotify("mynotification");

    // Wait a bit to let the notify come through... Changed this so the test takes ~2 seconds
    // less to run and is still as effective.
    PGNotification[] notifications = null;
    try {
      int retries = 20;
      while (retries-- > 0
        && (notifications = conn.unwrap(PGConnection.class).getNotifications()) == null ) {
        Thread.sleep(100);
      }
    } catch (InterruptedException ie) {
    }

    assertNotNull("Notification is expected to be delivered when subscription was created"
            + " before sending notification", notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  /**
   * To test timeouts we have to send the notification from another thread, because we
   * listener is blocking.
   */
  @Test(timeout = 60000)
  public void testAsyncNotifyWithTimeout() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Here we let the getNotifications() timeout.
    long startMillis = System.currentTimeMillis();
    PGNotification[] notifications = conn.unwrap(PGConnection.class).getNotifications(500);
    long endMillis = System.currentTimeMillis();
    long runtime = endMillis - startMillis;
    assertNull("There have been notifications, although none have been expected.",notifications);
    Assert.assertTrue("We didn't wait long enough! runtime=" + runtime, runtime > 450);

    stmt.close();
  }

  @Test(timeout = 60000)
  public void testAsyncNotifyWithTimeoutAndMessagesAvailableWhenStartingListening() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Now we check the case where notifications are already available while we are starting to
    // listen for notifications
    connectAndNotify("mynotification");

    PGNotification[] notifications = conn.unwrap(PGConnection.class).getNotifications(10000);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  @Test(timeout = 60000)
  public void testAsyncNotifyWithEndlessTimeoutAndMessagesAvailableWhenStartingListening() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Now we check the case where notifications are already available while we are waiting forever
    connectAndNotify("mynotification");

    PGNotification[] notifications = conn.unwrap(PGConnection.class).getNotifications(0);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  @Test(timeout = 60000)
  public void testAsyncNotifyWithTimeoutAndMessagesSendAfter() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Now we check the case where notifications are send after we have started to listen for
    // notifications
    new Thread( new Runnable() {
      public void run() {
        try {
          Thread.sleep(200);
        } catch (InterruptedException ie) {
        }
        connectAndNotify("mynotification");
      }
    }).start();

    PGNotification[] notifications = conn.unwrap(PGConnection.class).getNotifications(10000);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  @Test(timeout = 60000)
  public void testAsyncNotifyWithEndlessTimeoutAndMessagesSendAfter() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Now we check the case where notifications are send after we have started to listen for
    // notifications forever
    new Thread( new Runnable() {
      public void run() {
        try {
          Thread.sleep(200);
        } catch (InterruptedException ie) {
        }
        connectAndNotify("mynotification");
      }
    }).start();

    PGNotification[] notifications = conn.unwrap(PGConnection.class).getNotifications(0);
    assertNotNull(notifications);
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  @Test(timeout = 60000)
  public void testAsyncNotifyWithTimeoutAndSocketThatBecomesClosed() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Here we check what happens when the connection gets closed from another thread. This
    // should be able, and this test ensures that no synchronized statements will stop the
    // connection from becoming closed.
    new Thread( new Runnable() {
      public void run() {
        try {
          Thread.sleep(500);
        } catch (InterruptedException ie) {
        }
        try {
          conn.close();
        } catch (SQLException e) {
        }
      }
    }).start();

    try {
      conn.unwrap(PGConnection.class).getNotifications(40000);
      Assert.fail("The getNotifications(...) call didn't return when the socket closed.");
    } catch (SQLException e) {
      // We expected that
    }

    stmt.close();
  }

  private static void connectAndNotify(String channel) {
    Connection conn2 = null;
    try {
      conn2 = TestUtil.openDB();
      Statement stmt2 = conn2.createStatement();
      stmt2.executeUpdate("NOTIFY " + channel);
      stmt2.close();
    } catch (Exception e) {
      throw new RuntimeException("Couldn't notify '" + channel + "'.",e);
    } finally {
      try {
        conn2.close();
      } catch (SQLException e) {
      }
    }
  }

}
