---
layout: todos
title: PostgreSQL JDBC Todo
resource: ../media
nav: ../
---


# Todo List
***
					
* [Known Bugs](#Known_Bugs)
* [Compliance](#Compliance)
* [Performance](#Performance)
* [PG Extensions](#PG_Extensions)
* [Other](#Other)
* [Ideas](#Ideas)
* [Documentation](#Documentation)
* [Website](#Website)

***
<a name="Known_Bugs"></a>
## Known Bugs
					
* **[bugs]** Deallocating large numbers of server side statements can break the
	connection by filling network buffers.  This is a very, very low probability
	bug, but it is still possible. <a href="http://archives.postgresql.org/pgsql-jdbc/2004-12/msg00115.php">ref</a>
	&rarr;

***
<a name="Compliance"></a>
## Compliance
					
* **[JDBC1]** Implement Statement.setQueryTimeout. &rarr;
* **[JDBC2]** Sort DatabaseMetaData.getTypeInfo properly (by closest match). &rarr;
* **[JDBC2]** Implement SQLInput and SQLOutput to allow composite types to be used. &rarr;
* **[JDBC3]** Implement Statement.getGeneratedKeys. <a href="http://archives.postgresql.org/pgsql-jdbc/2004-09/msg00190.php">ref2</a> &rarr;
* **[JDBC3]** The JDBC 3 DatabaseMetaData methods sometimes return additional information.
	Currently we only return JDBC 2 data for these methods. <a href="http://archives.postgresql.org/pgsql-jdbc/2004-12/msg00038.php">ref</a>
	&rarr;
* **[JDBC3]** Implement Clob write/position methods. &rarr;

***
<a name="Performance"></a>
## Performance
					
* **[]** Add statement pooling to take advantage of server prepared statements. &rarr;
* **[]** Allow scrollable ResultSets to not fetch all results in one batch. &rarr;
* **[]** Allow refcursor ResultSets to not fetch all results in one batch. &rarr;
* **[]** Allow binary data transfers for all datatypes not just bytea. &rarr;

***
<a name="PG_Extensions"></a>
## PG Extensions
					
* **[]** Allow configuration of GUC parameters via the Connection URL or Datasource.
	The most obvious example of usefulness is search_path. <a href="http://archives.postgresql.org/pgsql-jdbc/2004-02/msg00022.php">ref</a>
	&rarr;

***
<a name="Other"></a>
## Other
					
* **[test]** Pass the JDBC CTS (Sun's test suite). &rarr;
* **[code]** Allow SSL to use client certificates.  This can probably be done with
	our existing SSLSocketFactory customization code, but it would be good to
	provide an example or other wrapper so a non-expert can set it up.
	<a href="http://archives.postgresql.org/pgsql-jdbc/2004-12/msg00077.php">ref1</a>,
	<a href="http://archives.postgresql.org/pgsql-jdbc/2004-12/msg00083.php">ref2</a> &rarr;
* **[code]** Currently the internal type cache is not schema aware. &rarr;
* **[code]** Need a much better lexer/parser than the ad hoc stuff in the driver.
	<a href="http://archives.postgresql.org/pgsql-jdbc/2004-09/msg00062.php">ref2</a> &rarr;

***
<a name="Ideas"></a>
## Ideas
					
* **[]** Allow Blob/Clob to operate on bytea/text data. <a href="http://archives.postgresql.org/pgsql-jdbc/2005-01/msg00058.php">ref</a>
	&rarr;
* **[]** Allow getByte/getInt/... to work on boolean values <a href="http://archives.postgresql.org/pgsql-jdbc/2005-01/msg00254.php">ref</a>
	&rarr;
* **[]** Add a URL parameter to make the driver not force a rollback on error for
	compatibility with other dbs.  The driver can wrap each statement in a Savepoint.
	<a href="http://archives.postgresql.org/pgsql-jdbc/2005-01/msg00131.php">ref</a> &rarr;
* **[]** Combine DatabaseMetaData efforts with pl/java. <a href="http://archives.postgresql.org/pgsql-jdbc/2005-02/msg00063.php">ref</a>
	&rarr;
* **[]**  ResultSetMetaData calls that run queries are cached on a per column basis, but
	it seems likely that they're going to be called for all columns, so try to issue
	one query per ResultSet, not per column. &rarr;
* **[]** Make PGConnection, PGStatement, ... extend java.sql.XXX <a href="http://archives.postgresql.org/pgsql-jdbc/2005-01/msg00223.php">ref</a>
	&rarr;

***
<a name="Documentation"></a>
## Documentation
					
* **[]** The PGResultSetMetaData interface is not mentioned. &rarr;
* **[]** Timestamp +/- Infinity values are not mentioned. &rarr;
* **[]** Async notifies are more async now. <a href="http://archives.postgresql.org/pgsql-jdbc/2005-04/msg00056.php">ref</a>
	&rarr;

***
<a name="Website"></a>
## Website
					
* **[]** Setup a cron job somewhere to build and deploy the sight on a daily
	basis to keep API changes and translations up to date.
	&rarr;							
* **[]** Add a daily git snapshot build to make the latest updates available.
	&rarr;