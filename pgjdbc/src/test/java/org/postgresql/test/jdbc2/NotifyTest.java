/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

class NotifyTest {
  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(conn);
  }

  @Test
  @Timeout(60)
  void testNotify() throws SQLException {
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

  @Test
  @Timeout(60)
  void notifyArgument() throws Exception {
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

  @Test
  @Timeout(60)
  void asyncNotify() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Notify on a separate connection to get an async notify on the first.
    connectAndNotify("mynotification");

    // Wait a bit to let the notify come through... Changed this so the test takes ~2 seconds
    // less to run and is still as effective.
    PGNotification[] notifications = null;
    PGConnection connection = conn.unwrap(PGConnection.class);
    for (int i = 0; i < 3000; i++) {
      notifications = connection.getNotifications();
      if (notifications.length > 0) {
        break;
      }
      Thread.sleep(10);
    }

    assertNotNull(notifications, "Notification is expected to be delivered when subscription was created"
            + " before sending notification");
    assertEquals(1, notifications.length);
    assertEquals("mynotification", notifications[0].getName());
    assertEquals("", notifications[0].getParameter());

    stmt.close();
  }

  /**
   * To test timeouts we have to send the notification from another thread, because we
   * listener is blocking.
   */
  @Test
  @Timeout(60)
  void asyncNotifyWithTimeout() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Here we let the getNotifications() timeout.
    long startMillis = System.currentTimeMillis();
    PGNotification[] notifications = conn.unwrap(PGConnection.class).getNotifications(500);
    long endMillis = System.currentTimeMillis();
    long runtime = endMillis - startMillis;
    assertEquals("[]", Arrays.asList(notifications).toString(), "There have been notifications, although none have been expected.");
    assertTrue(runtime > 450, "We didn't wait long enough! runtime=" + runtime);

    stmt.close();
  }

  @Test
  @Timeout(60)
  void asyncNotifyWithTimeoutAndMessagesAvailableWhenStartingListening() throws Exception {
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

  @Test
  @Timeout(60)
  void asyncNotifyWithEndlessTimeoutAndMessagesAvailableWhenStartingListening() throws Exception {
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

  @Test
  @Timeout(60)
  void asyncNotifyWithTimeoutAndMessagesSendAfter() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Now we check the case where notifications are send after we have started to listen for
    // notifications
    new Thread( new Runnable() {
      @Override
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

  @Test
  @Timeout(60)
  void asyncNotifyWithEndlessTimeoutAndMessagesSendAfter() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Now we check the case where notifications are send after we have started to listen for
    // notifications forever
    new Thread( new Runnable() {
      @Override
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

  @Test
  @Timeout(60)
  void asyncNotifyWithTimeoutAndSocketThatBecomesClosed() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.executeUpdate("LISTEN mynotification");

    // Here we check what happens when the connection gets closed from another thread. This
    // should be able, and this test ensures that no synchronized statements will stop the
    // connection from becoming closed.
    new Thread( new Runnable() {
      @Override
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
      fail("The getNotifications(...) call didn't return when the socket closed.");
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
      throw new RuntimeException("Couldn't notify '" + channel + "'.", e);
    } finally {
      try {
        conn2.close();
      } catch (SQLException e) {
      }
    }
  }

}
