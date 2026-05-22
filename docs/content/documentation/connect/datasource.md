---
title: "Connection Pools and Data Sources"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 10
toc: true
aliases:
    - "/documentation/head/ds-ds.html"
---

JDBC 2 introduced standard connection pooling features in an add-on API known as the JDBC 2.0 Optional Package
(also known as the JDBC 2.0 Standard Extension). These features have since been included in the core JDBC 3 API.

The JDBC API provides a client and a server interface for connection pooling. The client interface is `javax.sql.DataSource`,
which is what application code will typically use to acquire a pooled database connection. The server interface is
`javax.sql.ConnectionPoolDataSource` , which is how most application servers will interface with the PostgreSQL® JDBC driver.

In an application server environment, the application server configuration will typically refer to the PostgreSQL®
`ConnectionPoolDataSource` implementation, while the application component code will typically acquire a `DataSource`
implementation provided by the application server (not by PostgreSQL®).

For an environment without an application server, PostgreSQL® provides two implementations of `DataSource` which an
application can use directly. One implementation performs connection pooling, while the other simply provides access to
database connections through the `DataSource` interface without any pooling. Again, these implementations should not be
used in an application server environment unless the application server does not support the `ConnectionPoolDataSource` interface.

## Application Servers ConnectionPoolDataSource

PostgreSQL® includes one implementation of `ConnectionPoolDataSource` named `org.postgresql.ds.PGConnectionPoolDataSource` .

JDBC requires that a `ConnectionPoolDataSource` be configured via JavaBean properties, shown in
[Table 11.1, “`ConnectionPoolDataSource` Configuration Properties”](/documentation/datasource/#table111-connectionpooldatasource-configuration-properties),
so there are get and set methods for each of these properties.

##### Table 11.1.  `ConnectionPoolDataSource` Configuration Properties

|Property|Type| Description                                                                                                                                          |
|---|---|------------------------------------------------------------------------------------------------------------------------------------------------------|
|serverName|STRING| PostgreSQL® database server host name                                                                                                                |
|databaseName|STRING| PostgreSQL® database name                                                                                                                            |
|portNumber|INT| TCP port which the PostgreSQL® database server is listening on (or 0 to use the default port)                                                        |
|user|STRING| User used to make database connections                                                                                                               |
|password|STRING| Password used to make database connections                                                                                                           |
|ssl|BOOLEAN| If `true` , use SSL encrypted connections (default `false` )                                                                                         |
|sslfactory|STRING| Custom `javax.net.ssl.SSLSocketFactory` class name (see the section called [“Custom SSLSocketFactory”](ssl-factory.html))                            |
|defaultAutoCommit|BOOLEAN| Whether connections should have autocommit enabled or disabled when they are supplied to the caller. The default is `false` , to disable autocommit. |

Many application servers use a properties-style syntax to configure these properties, so it would not be unusual to enter
properties as a block of text. If the application server provides a single area to enter all the properties, they might
be listed like this:

 `serverName=localhost`

 `databaseName=test`

 `user=testuser`

 `password=testpassword`

Or, if semicolons are used as separators instead of newlines, it could look like this:

 `serverName=localhost;databaseName=test;user=testuser;password=testpassword`

## Applications DataSource

PostgreSQL® includes two implementations of `DataSource` , as shown in [Table 11.2, “`DataSource` Implementations”](/documentation/datasource/#table112datasource-implementations).

One that does pooling and the other that does not. The pooling implementation does not actually close connections when
the client calls the `close()` method, but instead returns the connections to a pool of available connections for other
clients to use.  This avoids any overhead of repeatedly opening and closing connections, and allows a large number of
clients to share a small number of database connections.

The pooling data-source implementation provided here is not the most feature-rich in the world. Among other things,
connections are never closed until the pool itself is closed; there is no way to shrink the pool.  As well, connections
requested for users other than the default configured user are not pooled. Its error handling sometimes cannot remove a
broken connection from the pool. In general it is not recommended to use the PostgreSQL® provided connection pool. Check
your application server or check out the excellent [jakarta commons DBCP](http://jakarta.apache.org/commons/dbcp/) project.

##### Table 11.2. `DataSource` Implementations

|Pooling|Implementation Class|
|---|---|
|No|`org.postgresql.ds.PGSimpleDataSource`|
|Yes|`org.postgresql.ds.PGPoolingDataSource`|

Both implementations use the same configuration scheme. JDBC requires that a `DataSource` be configured via JavaBean properties,
shown in [Table 11.3, “`DataSource` Configuration Properties”](/documentation/datasource/#table113-datasource-configuration-properties),
so there are get and set methods for each of these properties.

##### Table 11.3.  `DataSource` Configuration Properties

|Property|Type|Description|
|---|---|---|
|serverName|STRING|PostgreSQL® database server host name|
|databaseName|STRING|PostgreSQL® database name|
|portNumber|INT|TCP port which the PostgreSQL® database server is listening on (or 0 to use the default port)|
|user|STRING|User used to make database connections|
|password|STRING|Password used to make database connections|
|ssl|BOOLEAN|If true, use SSL encrypted connections (default false)|
|sslfactory|STRING|Custom javax.net.ssl. SSLSocketFactory class name (see the section called [“Custom SSLSocketFactory”](ssl-factory.html))|

The pooling implementation requires some additional configuration properties, which are shown in
[Table 11.4, “Additional Pooling `DataSource` Configuration Properties](/documentation/datasource/#table114additional-pooling-datasource-configuration-properties).

##### Table 11.4. Additional Pooling `DataSource` Configuration Properties

|Property|Type|Description|
|---|---|---|
|dataSourceName|STRING|Every pooling DataSource must have a unique name.|
|initialConnections|INT|The number of database connections to be created when the pool is initialized.|
|maxConnections|INT|The maximum number of open database connections to allow. When more connections are requested, the caller will hang until a connection is returned to the pool.|

[Example 11.1, “`DataSource` Code Example”](/documentation/datasource/#example111-datasource-code-example) shows an example
of typical application code using a pooling `DataSource`.

##### Example 11.1.  `DataSource` Code Example

Code to initialize a pooling `DataSource` might look like this:

```java
PGPoolingDataSource source = new PGPoolingDataSource();
source.setDataSourceName("A Data Source");
source.setServerNames(new String[] {
    "localhost"
});
source.setDatabaseName("test");
source.setUser("testuser");
source.setPassword("testpassword");
source.setMaxConnections(10);
```

> **Note**
>
> setServerName has been deprecated in favour of setServerNames. This was done to support multiple hosts.

Then code to use a connection from the pool might look like this.

> **Note**
>
> it is critical that the connections are eventually closed. Otherwise, the pool will “leak” connections and will eventually
> lock all the clients out.

```java
try (Connection conn = source.getConnection()) {
    // use connection
} catch (SQLException e) {
    // log error
}
```

## Data Sources and JNDI

All the `ConnectionPoolDataSource` and `DataSource` implementations can be stored in JNDI. In the case of the non-pooling
implementations, a new instance will be created every time the object is retrieved from JNDI, with the same settings as
the instance that was stored. For the pooling implementations, the same instance will be retrieved as long as it is available
(e.g., not a different JVM retrieving the pool from JNDI), or a new instance with the same settings created otherwise.

In the application server environment, typically the application server's `DataSource` instance will be stored in JNDI,
instead of the PostgreSQL® `ConnectionPoolDataSource` implementation.

In an application environment, the application may store the `DataSource` in JNDI so that it doesn't have to make a reference
to the `DataSource` available to all application components that may need to use it. An example of this is shown in
[Example 11.2, “`DataSource` JNDI Code Example”](/documentation/datasource/#example112-datasource-jndi-code-example).

##### Example 11.2.  `DataSource` JNDI Code Example

Application code to initialize a pooling `DataSource` and add it to JNDI might look like this:

```java
PGPoolingDataSource source = new PGPoolingDataSource();
source.setDataSourceName("A Data Source");
source.setServerName("localhost");
source.setDatabaseName("test");
source.setUser("testuser");
source.setPassword("testpassword");
source.setMaxConnections(10);
new InitialContext().rebind("DataSource", source);
```

Then code to use a connection from the pool might look like this:

```java
Connection conn = null;
try {
    DataSource source = (DataSource) new InitialContext().lookup("DataSource");
    conn = source.getConnection();
    // use connection
} catch (SQLException e) {
    // log error
} catch (NamingException e) {
    // DataSource wasn't found in JNDI
} finally {
    if (con != null) {
        try {
            conn.close();
        } catch (SQLException e) {}
    }
}
```

## Tomcat setup

> **NOTE**
>
> The postgresql.jar file must be placed in $CATALINA_HOME/common/lib in both Tomcat 4 and 5.

The absolute easiest way to set this up in either tomcat instance is to use the
admin web application that comes with Tomcat, simply add the datasource to the
context you want to use it in.

Setup for Tomcat 4 place the following inside the &lt; Context&gt; tag inside
conf/server.xml

```xml
<Resource name="jdbc/postgres" scope="Shareable" type="javax.sql.DataSource"/>
<ResourceParams name="jdbc/postgres">
	<parameter>
		<name>validationQuery</name>
		<value>select version();</value>
	</parameter>
	<parameter>
		<name>url</name>
		<value>jdbc:postgresql://localhost/davec</value>
	</parameter>
	<parameter>
		<name>password</name>
		<value>davec</value>
	</parameter>
	<parameter>
		<name>maxActive</name>
		<value>4</value>
	</parameter>
	<parameter>
		<name>maxWait</name>
		<value>5000</value>
	</parameter>
	<parameter>
		<name>driverClassName</name>
		<value>org.postgresql.Driver</value>
	</parameter>
	<parameter>
		<name>username</name>
		<value>davec</value>
	</parameter>
	<parameter>
		<name>maxIdle</name>
		<value>2</value>
	</parameter>
</ResourceParams>
```

Setup for Tomcat 5, you can use the above method, except that it goes inside the
&lt; DefaultContext&gt; tag inside the &lt; Host&gt; tag. eg. &lt; Host&gt; ... &lt; DefaultContext&gt; ...

Alternatively there is a conf/Catalina/hostname/context.xml file. For example
`http://localhost:8080/servlet-example` has a directory `$CATALINA_HOME/conf/Catalina/localhost/servlet-example.xml` file.
Inside this file place the above xml inside the &lt; Context&gt; tag

Then you can use the following code to access the connection.

```java
import javax.naming.*;
import javax.sql.*;
import java.sql.*;
public class DBTest {

    String foo = "Not Connected";
    int bar = -1;

    public void init() {
        try {
            Context ctx = new InitialContext();
            if (ctx == null)
                throw new Exception("Boom - No Context");

            // /jdbc/postgres is the name of the resource above
            DataSource ds = (DataSource) ctx.lookup("java:comp/env/jdbc/postgres");

            if (ds != null) {
                Connection conn = ds.getConnection();

                if (conn != null) {
                    foo = "Got Connection " + conn.toString();
                    Statement stmt = conn.createStatement();
                    ResultSet rst = stmt.executeQuery("select id, foo, bar from testdata");

                    if (rst.next()) {
                        foo = rst.getString(2);
                        bar = rst.getInt(3);
                    }
                    conn.close();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getFoo() {
        return foo;
    }

    public int getBar() {
        return bar;
    }
}
```
