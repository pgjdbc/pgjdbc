---
layout: default_docs
title: Server Prepared Statements
header: Chapter 9. PostgreSQL™ Extensions to the JDBC API
resource: media
previoustitle: Listen / Notify
previous: listennotify.html
nexttitle: Chapter 10. Using the Driver in a Multithreaded or a Servlet Environment
next: thread.html
---

The PostgreSQL™ server allows clients to compile sql statements that are expected
to be reused to avoid the overhead of parsing and planning the statement for every
execution. This functionality is available at the SQL level via PREPARE and EXECUTE
beginning with server version 7.3, and at the protocol level beginning with server
version 7.4, but as Java developers we really just want to use the standard
`PreparedStatement` interface.

### Note

> Previous versions of the driver used PREPARE and EXECUTE to implement
server-prepared statements.  This is supported on all server versions beginning
with 7.3, but produced application-visible changes in query results, such as
missing ResultSet metadata and row update counts. The current driver uses the V3
protocol-level equivalents which avoid these changes in query results, but the
V3 protocol is only available beginning with server version 7.4. Enabling server-prepared
statements will have no affect when connected to a 7.3 server or when explicitly
using the V2 protocol to connect to a 7.4 server.

There are a number of ways to enable server side prepared statements depending on
your application's needs. The general method is to set a threshold for a
`PreparedStatement`. An internal counter keeps track of how many times the
statement has been executed and when it reaches the threshold it will start to
use server side prepared statements.

### Note

> Server side prepared statements are planned only once by the server. This avoids
the cost of replanning the query every time, but also means that the planner
cannot take advantage of the particular parameter values used in a particular
execution of the query. You should be cautious about enabling the use of server
side prepared statements globally.

<a name="server-prepared-statement-example"></a>
**Example 9.3. Using server side prepared statements**

import java.sql.*;

public class ServerSidePreparedStatement
{

	public static void main(String args[]) throws Exception
	{
		Class.forName("org.postgresql.Driver");
		String url = "jdbc:postgresql://localhost:5432/test";
		Connection conn = DriverManager.getConnection(url,"test","");

		PreparedStatement pstmt = conn.prepareStatement("SELECT ?");

		// cast to the pg extension interface
		org.postgresql.PGStatement pgstmt = pstmt.unwrap(org.postgresql.PGStatement.class);

		// on the third execution start using server side statements
		pgstmt.setPrepareThreshold(3);

		for (int i=1; i<=5; i++)
		{
			pstmt.setInt(1,i);
			boolean usingServerPrepare = pgstmt.isUseServerPrepare();
			ResultSet rs = pstmt.executeQuery();
			rs.next();
			System.out.println("Execution: "+i+", Used server side: " + usingServerPrepare + ", Result: "+rs.getInt(1));
			rs.close();
		}

		pstmt.close();
		conn.close();
	}
}

Which produces the expected result of using server side prepared statements upon
the third execution.

`Execution: 1, Used server side: false, Result: 1`  
`Execution: 2, Used server side: false, Result: 2`  
`Execution: 3, Used server side: true, Result: 3`  
`Execution: 4, Used server side: true, Result: 4`  
`Execution: 5, Used server side: true, Result: 5`

The example shown above requires the programmer to use PostgreSQL™ specific code
in a supposedly portable API which is not ideal. Also it sets the threshold only
for that particular statement which is some extra typing if we wanted to use that
threshold for every statement. Let's take a look at the other ways to set the
threshold to enable server side prepared statements.  There is already a hierarchy
in place above a `PreparedStatement`, the `Connection` it was created from, and
above that the source of the connection be it a `Datasource` or a URL. The server
side prepared statement threshold can be set at any of these levels such that
the value will be the default for all of it's children.

`// pg extension interfaces`  
`org.postgresql.PGConnection pgconn;`  
`org.postgresql.PGStatement pgstmt;`

`// set a prepared statement threshold for connections created from this url`  
`String url = "jdbc:postgresql://localhost:5432/test?prepareThreshold=3";`

`// see that the connection has picked up the correct threshold from the url`  
`Connection conn = DriverManager.getConnection(url,"test","");`  
`pgconn = conn.unwrap(org.postgresql.PGConnection.class);`  
`System.out.println(pgconn.getPrepareThreshold()); // Should be 3`

`// see that the statement has picked up the correct threshold from the connection`  
`PreparedStatement pstmt = conn.prepareStatement("SELECT ?");`  
`pgstmt = pstmt.unwrap(org.postgresql.PGStatement.class);`  
`System.out.println(pgstmt.getPrepareThreshold()); // Should be 3`

`// change the connection's threshold and ensure that new statements pick it up`  
`pgconn.setPrepareThreshold(5);`  
`PreparedStatement pstmt = conn.prepareStatement("SELECT ?");`  
`pgstmt = pstmt.unwrap(org.postgresql.PGStatement.class);`  
`System.out.println(pgstmt.getPrepareThreshold()); // Should be 5`
