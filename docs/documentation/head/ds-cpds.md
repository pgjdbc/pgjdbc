---
layout: default_docs
title: Application Servers ConnectionPoolDataSource
header: Chapter 11. Connection Pools and Data Sources
resource: /documentation/head/media
previoustitle: Chapter 11. Connection Pools and Data Sources
previous: datasource.html
nexttitle: Applications DataSource
next: ds-ds.html
---

PostgreSQL™ includes one implementation of `ConnectionPoolDataSource` named
`org.postgresql.ds.PGConnectionPoolDataSource`.

JDBC requires that a `ConnectionPoolDataSource` be configured via JavaBean
properties, shown in [Table 11.1, “`ConnectionPoolDataSource` Configuration Properties”](ds-cpds.html#ds-cpds-props),
so there are get and set methods for each of these properties.

<a name="ds-cpds-props"></a>
**Table 11.1. `ConnectionPoolDataSource` Configuration Properties**

<table summary="ConnectionPoolDataSource Configuration Properties" class="CALSTABLE" border="1">
  <tr>
    <th>Property</th>
    <th>Type</th>
    <th>Description</th>
  </tr>
  <tbody>
    <tr>
      <td>serverName</td>
      <td>STRING</td>
      <td>PostgreSQL™ database server
host name</td>
    </tr>
    <tr>
      <td>databaseName</td>
      <td>STRING</td>
      <td>PostgreSQL™ database name</td>
    </tr>
    <tr>
      <td>portNumber</td>
      <td>INT</td>
      <td> TCP port which the PostgreSQL™

database server is listening on (or 0 to use the default port) </td>
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
      <td> If `true`, use SSL encrypted
connections (default `false`) </td>
    </tr>
    <tr>
      <td>sslfactory</td>
      <td>STRING</td>
      <td> Custom `javax.net.ssl.SSLSocketFactory`
class name (see the section called [“Custom
SSLSocketFactory”](ssl-factory.html)) </td>
    </tr>
    <tr>
      <td>defaultAutoCommit</td>
      <td>BOOLEAN</td>
      <td> Whether connections should have autocommit enabled or
disabled when they are supplied to the caller. The default is `false`, to disable autocommit. </td>
    </tr>
  </tbody>
</table>

Many application servers use a properties-style syntax to configure these
properties, so it would not be unusual to enter properties as a block of text.
If the application server provides a single area to enter all the properties,
they might be listed like this:

`serverName=localhost`  
`databaseName=test`  
`user=testuser`  
`password=testpassword`

Or, if semicolons are used as separators instead of newlines, it could look like
this:

`serverName=localhost;databaseName=test;user=testuser;password=testpassword`
