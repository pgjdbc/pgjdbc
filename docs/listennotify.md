---
layout: default_docs
title: Listen / Notify
header: Chapter 9. PostgreSQL™ Extensions to the JDBC API
resource: media
previoustitle: Large Objects
previous: largeobjects.html
nexttitle: Server Prepared Statements
next: server-prepare.html
---

Listen and Notify provide a simple form of signal or interprocess communication
mechanism for a collection of processes accessing the same PostgreSQL™ database.
For more information on notifications consult the main server documentation. This
section only deals with the JDBC specific aspects of notifications.

Standard `LISTEN`, `NOTIFY`, and `UNLISTEN` commands are issued via the standard
`Statement` interface. To retrieve and process retrieved notifications the
`Connection` must be cast to the PostgreSQL™ specific extension interface
`PGConnection`. From there the `getNotifications()` method can be used to retrieve
any outstanding notifications.

### Note

> A key limitation of the JDBC driver is that it cannot receive asynchronous
notifications and must poll the backend to check if any notifications were issued.

<a name="listen-notify-example"></a>
**Example 9.2. Receiving Notifications**

<pre><code>
import java.sql.*;

public class NotificationTest
{
	public static void main(String args[]) throws Exception
	{
		Class.forName("org.postgresql.Driver");
		String url = "jdbc:postgresql://localhost:5432/test";

		// Create two distinct connections, one for the notifier
		// and another for the listener to show the communication
		// works across connections although this example would
		// work fine with just one connection.

		Connection lConn = DriverManager.getConnection(url,"test","");
		Connection nConn = DriverManager.getConnection(url,"test","");

		// Create two threads, one to issue notifications and
		// the other to receive them.

		Listener listener = new Listener(lConn);
		Notifier notifier = new Notifier(nConn);
		listener.start();
		notifier.start();
	}
}
</code></pre>

<pre><code>
class Listener extends Thread
{
	private Connection conn;
	private org.postgresql.PGConnection pgconn;

	Listener(Connection conn) throws SQLException
	{
		this.conn = conn;
		this.pgconn = conn.unwrap(org.postgresql.PGConnection.class);
		Statement stmt = conn.createStatement();
		stmt.execute("LISTEN mymessage");
		stmt.close();
	}

	public void run()
	{
		while (true)
		{
			try
			{
				// issue a dummy query to contact the backend
				// and receive any pending notifications.

				Statement stmt = conn.createStatement();
				ResultSet rs = stmt.executeQuery("SELECT 1");
				rs.close();
				stmt.close();

				org.postgresql.PGNotification notifications[] = pgconn.getNotifications();
				
				if (notifications != null)
				{
					for (int i=0; i&lt;notifications.length; i++)
						System.out.println("Got notification: " + notifications[i].getName());
				}

				// wait a while before checking again for new
				// notifications
				
				Thread.sleep(500);
			}
			catch (SQLException sqle)
			{
				sqle.printStackTrace();
			}
			catch (InterruptedException ie)
			{
				ie.printStackTrace();
			}
		}
	}
}
</code></pre>

<pre><code>
class Notifier extends Thread
{
	private Connection conn;

	public Notifier(Connection conn)
	{
		this.conn = conn;
	}

	public void run()
	{
		while (true)
		{
			try
			{
				Statement stmt = conn.createStatement();
				stmt.execute("NOTIFY mymessage");
				stmt.close();
				Thread.sleep(2000);
			}
			catch (SQLException sqle)
			{
				sqle.printStackTrace();
			}
			catch (InterruptedException ie)
			{
				ie.printStackTrace();
			}
		}
	}

}
</code></pre>
