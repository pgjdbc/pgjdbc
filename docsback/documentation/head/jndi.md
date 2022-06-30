---
layout: default_docs
title: Data Sources and JNDI
header: Chapter 11. Connection Pools and Data Sources
resource: /documentation/head/media
previoustitle: Tomcat setup
previous: tomcat.html
nexttitle: Chapter 12. Logging with java.util.logging
next: logging.html
---

All the `ConnectionPoolDataSource` and `DataSource` implementations can be stored
in JNDI. In the case of the nonpooling implementations, a new instance will be
created every time the object is retrieved from JNDI, with the same settings as
the instance that was stored. For the pooling implementations, the same instance
will be retrieved as long as it is available (e.g., not a different JVM retrieving
the pool from JNDI), or a new instance with the same settings created otherwise.

In the application server environment, typically the application server's
`DataSource` instance will be stored in JNDI, instead of the PostgreSQL™
`ConnectionPoolDataSource` implementation.

In an application environment, the application may store the `DataSource` in JNDI
so that it doesn't have to make a reference to the `DataSource` available to all
application components that may need to use it. An example of this is shown in
[Example 11.2, “`DataSource` JNDI Code Example”](jndi.html#ds-jndi).

<a name="ds-jndi"></a>
**Example 11.2. `DataSource` JNDI Code Example**

Application code to initialize a pooling `DataSource` and add it to JNDI might
look like this:

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
try
{
    DataSource source = (DataSource)new InitialContext().lookup("DataSource");
    conn = source.getConnection();
    // use connection
}
catch (SQLException e)
{
    // log error
}
catch (NamingException e)
{
    // DataSource wasn't found in JNDI
}
finally
{
    if (con != null)
    {
        try { conn.close(); } catch (SQLException e) {}
    }
}
```
