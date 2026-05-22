---
title: "Listen / Notify"
description: "Receiving PostgreSQL `LISTEN` / `NOTIFY` events through `PGConnection.getNotifications()`, the blocking and non-blocking variants, and the no-background-thread caveat: the application is responsible for draining the socket."
date: 2026-05-13T00:00:00Z
draft: false
weight: 2
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/server-prepare/#listen--notify/"
---

Listen and Notify provide a simple signal or interprocess communication mechanism for a collection of processes accessing the same PostgreSQL® database. For more information on notifications, consult the main server documentation. This section only deals with the JDBC-specific aspects of notifications.

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- PGConnection.java | pgjdbc/src/main/java/org/postgresql/PGConnection.java | 55-76
- PgConnection.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgConnection.java | 1243-1253
- NotifyTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/NotifyTest.java | 51-80
{{< /review >}}

Standard `LISTEN`, `NOTIFY`, and `UNLISTEN` commands are issued via the standard `Statement` interface. To retrieve and process notifications, the `Connection` must be unwrapped as the PostgreSQL-specific extension interface `PGConnection`. From there the `getNotifications()` method can be used to retrieve any outstanding notifications.

> **NOTE**
>
> pgJDBC has no background reader thread, so client code must call `getNotifications()` itself to drain the notifications the server has pushed onto the socket. The no-argument form returns immediately with whatever is buffered; `getNotifications(timeoutMillis)` blocks up to `timeoutMillis` (or forever when `timeoutMillis == 0`) until at least one notification arrives. While that call is blocking, other threads cannot issue statements on the same connection; other connections are unaffected.

## Example: receiving notifications

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- NotifyTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/NotifyTest.java | 87-112
- NotifyTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/NotifyTest.java | 121-224
- QueryExecutorImpl.java | pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java | 902-970
{{< /review >}}

```java
import java.sql.*;

public class NotificationTest {
    public static void main(String args[]) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/test";

        // Create two distinct connections, one for the notifier
        // and another for the listener to show the communication
        // works across connections although this example would
        // work fine with just one connection.

        Connection lConn = DriverManager.getConnection(url, "test", "");
        Connection nConn = DriverManager.getConnection(url, "test", "");

        // Create two threads, one to issue notifications and
        // the other to receive them.

        Listener listener = new Listener(lConn);
        Notifier notifier = new Notifier(nConn);
        listener.start();
        notifier.start();
    }
}

class Listener extends Thread {
    private Connection conn;
    private org.postgresql.PGConnection pgconn;

    Listener(Connection conn) throws SQLException {
        this.conn = conn;
        this.pgconn = conn.unwrap(org.postgresql.PGConnection.class);
        Statement stmt = conn.createStatement();
        stmt.execute("LISTEN mymessage");
        stmt.close();
    }

    public void run() {
        try {
            while (true) {
                org.postgresql.PGNotification notifications[] = pgconn.getNotifications();

                // If this thread is the only one that uses the connection, pass a timeout
                // to wake up as soon as a notification arrives (up to the timeout):
                // org.postgresql.PGNotification notifications[] = pgconn.getNotifications(10000);

                for (int i = 0; i < notifications.length; i++) {
                    // PGNotification also exposes getParameter() (the payload from NOTIFY channel,
                    // 'payload', supported since PG 9.0) and getPID() of the sending backend.
                    System.out.println("Got notification: " + notifications[i].getName());
                }

                // wait a while before checking again for new
                // notifications

                Thread.sleep(500);
            }
        } catch (SQLException sqle) {
            sqle.printStackTrace();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }
    }
}

class Notifier extends Thread {
    private Connection conn;

    public Notifier(Connection conn) {
        this.conn = conn;
    }

    public void run() {
        while (true) {
            try {
                Statement stmt = conn.createStatement();
                stmt.execute("NOTIFY mymessage");
                stmt.close();
                Thread.sleep(2000);
            } catch (SQLException sqle) {
                sqle.printStackTrace();
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        }
    }
}
```
