---
layout: default_docs
title: Applications DataSource
header: Chapter 11. Connection Pools and Data Sources
resource: /documentation/head/media
previoustitle: Application Servers ConnectionPoolDataSource
previous: ds-cpds.html
nexttitle: Tomcat setup
next: tomcat.html
---

PostgreSQL™ includes two implementations of `DataSource`, as shown in [Table 11.2, “`DataSource` Implementations”](ds-ds.html#ds-ds-imp).
One that does pooling and the other that does not. The pooling implementation
does not actually close connections when the client calls the `close` method,
but instead returns the connections to a pool of available connections for other
clients to use.  This avoids any overhead of repeatedly opening and closing
connections, and allows a large number of clients to share a small number of
database connections.

The pooling data-source implementation provided here is not the most feature-rich
in the world. Among other things, connections are never closed until the pool
itself is closed; there is no way to shrink the pool.  As well, connections
requested for users other than the default configured user are not pooled. Its
error handling sometimes cannot remove a broken connection from the pool. In
general it is not recommended to use the PostgreSQL™ provided connection pool.
Check your application server or check out the excellent [jakarta commons DBCP](http://jakarta.apache.org/commons/dbcp/)
project.

<a name="ds-ds-imp"></a>
**Table 11.2. `DataSource` Implementations**

<table summary="DataSource Implementations" class="CALSTABLE" border="1">
  <tr>
    <th>Pooling</th>
    <th>Implementation Class</th>
  </tr>
  <tbody>
    <tr>
      <td>No</td>
      <td>`org.postgresql.ds.PGSimpleDataSource</td>
    </tr>
    <tr>
      <td>Yes</td>
      <td>`org.postgresql.ds.PGPoolingDataSource</td>
    </tr>
  </tbody>
</table>

Both implementations use the same configuration scheme. JDBC requires that a
`DataSource` be configured via JavaBean properties, shown in [Table 11.3, “`DataSource` Configuration Properties”](ds-ds.html#ds-ds-props),
so there are get and set methods for each of these properties.

<a name="ds-ds-props"></a>
**Table 11.3. `DataSource` Configuration Properties**

<table summary="DataSource Configuration Properties" class="CALSTABLE" border="1">
  <tr>
    <th>Property</th>
    <th>Type</th>
    <th>Description</th>
  </tr>
  <tbody>
    <tr>
      <td>serverName</td>
      <td>STRING</td>
      <td>PostgreSQL™ database server host name</td>
    </tr>
    <tr>
      <td>databaseName</td>
      <td>STRING</td>
      <td>PostgreSQL™ database name</td>
    </tr>
    <tr>
      <td>portNumber</td>
      <td>INT</td>
      <td>TCP port which the PostgreSQL™
database server is listening on (or 0 to use the default port)</td>
    </tr>
    <tr>
      <td>user</td>
      <td>STRING</td>
      <td>User used to make database connections</td>
    </tr>
    <tr>
      <td>password</td>
      <td>STRING</td>
      <td>Password used to make database connections</td>
    </tr>
    <tr>
      <td>ssl</td>
      <td>BOOLEAN</td>
      <td> If true, use SSL encrypted
connections (default false) </td>
    </tr>
    <tr>
      <td>sslfactory</td>
      <td>STRING</td>
      <td> Custom javax.net.ssl.SSLSocketFactory
class name (see the section called [“Custom
SSLSocketFactory”](ssl-factory.html))</td>
    </tr>
  </tbody>
</table>

The pooling implementation requires some additional configuration properties,
which are shown in [Table 11.4, “Additional Pooling `DataSource` Configuration Properties](ds-ds.html#ds-ds-xprops).

<a name="ds-ds-xprops"></a>
**Table 11.4. Additional Pooling `DataSource` Configuration Properties**

<table summary="Additional Pooling DataSource Configuration Properties" class="CALSTABLE" border="1">
  <tr>
    <th>Property</th>
    <th>Type</th>
    <th>Description</th>
  </tr>
  <tbody>
    <tr>
      <td>dataSourceName</td>
      <td>STRING</td>
      <td>Every pooling DataSource must
have a unique name.</td>
    </tr>
    <tr>
      <td>initialConnections</td>
      <td>INT</td>
      <td>The number of database connections to be created when the
pool is initialized.</td>
    </tr>
    <tr>
      <td>maxConnections</td>
      <td>INT</td>
      <td>The maximum number of open database connections to allow.
When more connections are requested, the caller will hang until a
connection is returned to the pool.</td>
    </tr>
  </tbody>
</table>

[Example 11.1, “`DataSource` Code Example”](ds-ds.html#ds-example) shows an example
of typical application code using a pooling `DataSource`.

<a name="ds-example"></a>
**Example 11.1. `DataSource` Code Example**

Code to initialize a pooling `DataSource` might look like this:

```java
PGPoolingDataSource source = new PGPoolingDataSource();
source.setDataSourceName("A Data Source");
source.setServerNames(new String[] {"localhost"});
source.setDatabaseName("test");
source.setUser("testuser");
source.setPassword("testpassword");
source.setMaxConnections(10);
```

note: setServerName has been deprecated in favour of setServerNames. This was
done to support multiple hosts.

Then code to use a connection from the pool might look like this. Note that it
is critical that the connections are eventually closed.  Else the pool will
“leak” connections and will eventually lock all the clients out.

```java
try (Connection conn = source.getConnection())
{
    // use connection
}
catch (SQLException e)
{
    // log error
}
```
