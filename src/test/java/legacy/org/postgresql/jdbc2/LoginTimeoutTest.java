/*-------------------------------------------------------------------------
*
* Copyright (c) 2005-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

import java.net.Socket;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.io.IOException;
import java.util.Properties;

public class LoginTimeoutTest extends TestCase
{
    public LoginTimeoutTest(String name)
    {
        super(name);
    }

    public void setUp() throws Exception {
        TestUtil.initDriver(); // Set up log levels, etc.
    }

    public void testIntTimeout() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", TestUtil.getUser());
        props.setProperty("password", TestUtil.getPassword());
        props.setProperty("loginTimeout", "10");

        Connection conn = java.sql.DriverManager.getConnection(TestUtil.getURL(), props);
        conn.close();
    }

    public void testFloatTimeout() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", TestUtil.getUser());
        props.setProperty("password", TestUtil.getPassword());
        props.setProperty("loginTimeout", "10.0");

        Connection conn = java.sql.DriverManager.getConnection(TestUtil.getURL(), props);
        conn.close();
    }

    public void testZeroTimeout() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", TestUtil.getUser());
        props.setProperty("password", TestUtil.getPassword());
        props.setProperty("loginTimeout", "0");

        Connection conn = java.sql.DriverManager.getConnection(TestUtil.getURL(), props);
        conn.close();
    }

    public void testNegativeTimeout() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", TestUtil.getUser());
        props.setProperty("password", TestUtil.getPassword());
        props.setProperty("loginTimeout", "-1");

        Connection conn = java.sql.DriverManager.getConnection(TestUtil.getURL(), props);
        conn.close();
    }

    public void testBadTimeout() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", TestUtil.getUser());
        props.setProperty("password", TestUtil.getPassword());
        props.setProperty("loginTimeout", "zzzz");

        Connection conn = java.sql.DriverManager.getConnection(TestUtil.getURL(), props);
        conn.close();
    }

    private static class TimeoutHelper implements Runnable {
        TimeoutHelper() throws IOException {
            this.listenSocket = new ServerSocket(0, 1, InetAddress.getLocalHost());
        }

        String getHost() {
            return listenSocket.getInetAddress().getHostAddress();
        }

        int getPort() {
            return listenSocket.getLocalPort();
        }

        public void run() {
            try {
                Socket newSocket = listenSocket.accept();
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    // Ignore it.
                }
                newSocket.close();
            } catch (IOException e) {
                // Ignore it.
            }
        }

        void kill() {
            try {
                listenSocket.close();
            } catch (IOException e) {}
        }

        private final ServerSocket listenSocket;
    }


    public void testTimeoutOccurs() throws Exception {
        // Spawn a helper thread to accept a connection and do nothing with it;
        // this should trigger a timeout.
        TimeoutHelper helper = new TimeoutHelper();
        new Thread(helper, "timeout listen helper").start();
        
        try {
            String url = "jdbc:postgresqllegacy://" + helper.getHost() + ":" + helper.getPort() + "/dummy";
            Properties props = new Properties();
            props.setProperty("user", "dummy");
            props.setProperty("loginTimeout", "5");
            
            // This is a pretty crude check, but should help distinguish
            // "can't connect" from "timed out".
            long startTime = System.currentTimeMillis();
            Connection conn = null;
            try {
                conn = java.sql.DriverManager.getConnection(url, props);
                fail("connection was unexpectedly successful");
            } catch (SQLException e) {
                // Ignored.
            } finally {
                if (conn != null)
                    conn.close();
            }
            
            long endTime = System.currentTimeMillis();
            assertTrue(endTime > startTime + 2500);
        } finally {
            helper.kill();
        }
    }
}


        
