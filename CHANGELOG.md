# Changelog
Notable changes since version 42.0.0, read the complete [History of Changes](https://jdbc.postgresql.org/documentation/changelog.html).

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/).

## [Unreleased]
### Changed
- Rename source distribution archive to `postgresql-$version-jdbc-src.tar.gz`, and add top-level archive folder
- add the ability to connect with a GSSAPI encrypted connection. As of PostgreSQL version 12 GSSAPI encrypted connections
are possible. Now the driver will attempt to connect to the server with a GSSAPI encrypted connection. If that fails then
attempt an SSL connection, finally falling back to a plain text connection. All of this is controlled using both the gssEncMode
and sslMode parameters which, in concert with pg_hba.conf, determine if a particular mode is allowed and or required.

### Added
- Verify nullness with CheckerFramework

## [42.2.15] (2020-06-19)
### Changed
- Source release archive shades dependencies (scram) by default. It affects only postgresql-version-src.tar.gz release artifact.  

## [42.2.14] (2020-06-10)
### Changed
- Reverted com.github.waffle:waffle-jna, org.osgi:org.osgi.core, org.osgi:org.osgi.enterprise dependencies to optional=true in Maven [PR 1797](https://github.com/pgjdbc/pgjdbc/pull/1797).

## [42.2.13] (2020-06-04)

**Notable Changes**

- Security: The primary reason to release this version and to continue the 42.2.x branch is for CVE-2020-13692.
Reported by David Dworken, this is an XXE and more information can be found [here](https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html).
Sehrope Sarkuni reworked the XML parsing to provide a solution in commit [14b62aca4](https://github.com/pgjdbc/pgjdbc/commit/14b62aca4764d496813f55a43d050b017e01eb65).
- The build system has been changed to Gradle thanks to Vladimir [PR 1627](https://github.com/pgjdbc/pgjdbc/pull/1627).
- Regression: com.github.waffle:waffle-jna, org.osgi:org.osgi.core, org.osgi:org.osgi.enterprise dependencies are listed as non-optional [issue 1975](https://github.com/pgjdbc/pgjdbc/issues/1795).

### Changed

### Added
- jre-6 was added back to allow us to release fixes for all artifacts in the 42.2.x branch [PR 1787](https://github.com/pgjdbc/pgjdbc/pull/1787)

### Fixed
- I/O error ru translation [PR 1756](https://github.com/pgjdbc/pgjdbc/pull/1756)
- Issue [1771](https://github.com/pgjdbc/pgjdbc/issues/1771)  PgDatabaseMetaData.getFunctions() returns
 procedures fixed in [PR 1774](https://github.com/pgjdbc/pgjdbc/pull/1774)
- getTypeMap() returning null [PR 1781](https://github.com/pgjdbc/pgjdbc/pull/1774)
- Updated openssl example command [PR 1763](https://github.com/pgjdbc/pgjdbc/pull/1763)
- fix documentation with ordered list to be displayed correctly [PR 1783](https://github.com/pgjdbc/pgjdbc/pull/1783)

## [42.2.12] (2020-03-31)

**Notable changes**

We have released 42.2.12 to correct regressions in this version: Specifically
- [PR 1729](https://github.com/pgjdbc/pgjdbc/pull/1729) was reverted as this is a breaking change
- [PR 1719](https://github.com/pgjdbc/pgjdbc/pull/1719) has been reverted as it introduced errors in the PgType Cache

We recommend that version 42.2.11 not be used.
### Changed
 - reverted [PR 1729](https://github.com/pgjdbc/pgjdbc/pull/1729)  throw an error instead of silently rolling back a commit error. 
 This change introduced a breaking change which will be moved to 42.3.0
 - reverted [PR 1719](https://github.com/pgjdbc/pgjdbc/pull/1719)  add support for full names of data types (#1719)


## [42.2.11] (2020-03-07)

**Notable changes**
As mentioned above this version is broken and should not be used.
### Changed
 - Reverted [PR 1641](https://github.com/pgjdbc/pgjdbc/pull/1252). The driver will now wait for EOF when sending cancel signals. 
 - `DatabaseMetaData#getProcedures` returns only procedures (not functions) for PostgreSQL 11+ [PR 1723](https://github.com/pgjdbc/pgjdbc/pull/1723)
 - Convert silent rollbacks into exception if application sends `commit` or `xa.prepare` command [PR 1729](https://github.com/pgjdbc/pgjdbc/pull/1729)

### Added
 - feat: `raiseExceptionOnSilentRollback` connection option to configure if silent rollback should raise an exception [PR 1729](https://github.com/pgjdbc/pgjdbc/pull/1729)
 - feat: Expose `ByteStreamWriter` in CopyManager [PR 1702](https://github.com/pgjdbc/pgjdbc/pull/1702)
 - feat: add way to distinguish base and partitioned tables in PgDatabaseMetaData.getTables [PR 1708](https://github.com/pgjdbc/pgjdbc/pull/1708)
 - refactor: introduce tuple abstraction (rebased) [PR 1701](https://github.com/pgjdbc/pgjdbc/pull/1701)
 - refactor: make PSQLState enum consts for integrity constraint violations [PR 1699](https://github.com/pgjdbc/pgjdbc/pull/1699)
 - test: add makefile to create ssl certs [PR 1706](https://github.com/pgjdbc/pgjdbc/pull/1706)

### Fixed
 - fix: Always use `.` as decimal separator in PGInterval [PR 1705](https://github.com/pgjdbc/pgjdbc/pull/1705)
 - fix: allow DatabaseMetaData.getColumns to describe an unset scale [PR 1716](https://github.com/pgjdbc/pgjdbc/pull/1716)

### Changed
 - Build system update from Maven to Gradle [PR 1627](https://github.com/pgjdbc/pgjdbc/pull/1627)

### Added
 - docker-compose image for creating test databases (see `docker` folder)

## [42.2.10] (2020-01-30)
### Changed
 - (!) Regression: remove receiving EOF from backend after cancel [PR 1641](https://github.com/pgjdbc/pgjdbc/pull/1252). The regression is that the subsequent query might receive the cancel signal.

### Added
 - Add maxResultBuffer property [PR 1657](https://github.com/pgjdbc/pgjdbc/pull/1657)
 - add caller push of binary data (rebase of #953) [PR 1659](https://github.com/pgjdbc/pgjdbc/pull/1659)
 
### Fixed
 - Cleanup PGProperty, sort values, and add some missing to docs [PR 1686](https://github.com/pgjdbc/pgjdbc/pull/1686)
 - Fixing LocalTime rounding (losing precision) [PR 1570](https://github.com/pgjdbc/pgjdbc/pull/1570)
 - Network Performance of PgDatabaseMetaData.getTypeInfo() method [PR 1668](https://github.com/pgjdbc/pgjdbc/pull/1668)
 - Issue #1680 updating a boolean field requires special handling to set it to t or f instead of true or false [PR 1682](https://github.com/pgjdbc/pgjdbc/pull/1682)
 - bug in pgstream for replication [PR 1681](https://github.com/pgjdbc/pgjdbc/pull/1681)
 - Issue #1677 NumberFormatException when fetching PGInterval with small value [PR 1678](https://github.com/pgjdbc/pgjdbc/pull/1678)
 - Metadata queries improvements with large schemas. [PR 1673](https://github.com/pgjdbc/pgjdbc/pull/1673)
 - Utf 8 encoding optimizations [PR 1444](https://github.com/pgjdbc/pgjdbc/pull/1444)
 - interval overflow [PR 1658](https://github.com/pgjdbc/pgjdbc/pull/1658)
 - Issue #1482 where the port was being added to the GSSAPI service name [PR 1651](https://github.com/pgjdbc/pgjdbc/pull/1651)
 - remove receiving EOF from backend after cancel since according to protocol the server closes the connection once cancel is sent (connection reset exception is always thrown) [PR 1641](https://github.com/pgjdbc/pgjdbc/pull/1641)
 - Unable to register out parameter Issue #1646 [PR 1648](https://github.com/pgjdbc/pgjdbc/pull/1648)
  
## [42.2.9] (2019-12-06)
### Changed

### Added
 - read only transactions [PR 1252](https://github.com/pgjdbc/pgjdbc/pull/1252)
 - pkcs12 key functionality [PR 1599](https://github.com/pgjdbc/pgjdbc/pull/1599)
 - new "escapeSyntaxCallMode" connection property [PR 1560](https://github.com/pgjdbc/pgjdbc/pull/1560)
 - connection property to limit server error detail in exception exceptions [PR 1579](https://github.com/pgjdbc/pgjdbc/pull/1579)
 - cancelQuery() to PGConnection public interface [PR 1157](https://github.com/pgjdbc/pgjdbc/pull/1157) 
 - support for large update counts (JDBC 4.2) [PR 935](https://github.com/pgjdbc/pgjdbc/pull/935)
 - Add Binary Support for Oid.NUMERIC and Oid.NUMERIC_ARRAY [PR 1636](https://github.com/pgjdbc/pgjdbc/pull/1636) 
 
### Fixed
 - issue 716 getTypeInfo() may not return data in the order specified in Oracle documentation [PR 1506](https://github.com/pgjdbc/pgjdbc/pull/1506)
 - PgSQLXML setCharacterStream() results in null value  [PR 1608](https://github.com/pgjdbc/pgjdbc/pull/1608)
 - get correct column length for simple domains [PR 1605](https://github.com/pgjdbc/pgjdbc/pull/1605)
 - NPE as a result of calling executeQuery twice on a statement fixes issue [#684](https://github.com/pgjdbc/pgjdbc/issues/684) [PR 1610] (https://github.com/pgjdbc/pgjdbc/pull/1610)
 - handle numeric domain types [PR 1611](https://github.com/pgjdbc/pgjdbc/pull/1611) 
 - pginterval to take iso8601 strings [PR 1612](https://github.com/pgjdbc/pgjdbc/pull/1612)
 - remove currentTimeMillis from code, tests are OK [PR 1617](https://github.com/pgjdbc/pgjdbc/pull/1617) 
 - NPE when calling setNull on a PreparedStatement with no parameters [PR 1620](https://github.com/pgjdbc/pgjdbc/pull/1620)
 - allow OUT parameter registration when using CallableStatement native CALL [PR 1561](https://github.com/pgjdbc/pgjdbc/pull/1561)
 - add release save point into execute with batch [PR 1583](https://github.com/pgjdbc/pgjdbc/pull/1583) 
 - Prevent use of extended query protocol for BEGIN before COPY [PR 1639](https://github.com/pgjdbc/pgjdbc/pull/1639)

## [42.2.8] (2019-09-13)
### Changed

### Added

### Fixed

* fix: Revert inet default Java type to PGObject and handle values with net masks [PR 1568](https://github.com/pgjdbc/pgjdbc/pull/1568) 

## [42.2.7] (2019-09-03)
### Changed


### Added
- Expose parameter status messages (GUC_REPORT) to the user [PR 1435](https://github.com/pgjdbc/pgjdbc/pull/1435)
- Add automatic module name to manifest for jdk9+ [PR 1538](https://github.com/pgjdbc/pgjdbc/pull/1538)
- Log ignoring rollback when no transaction in progress [PR 1549](https://github.com/pgjdbc/pgjdbc/pull/1549)
- Map inet type to InetAddress [PR 1527](https://github.com/pgjdbc/pgjdbc/pull/1527) [issue 1134](https://github.com/pgjdbc/pgjdbc/issues/1134)

### Fixed
- fix [issue 1547](https://github.com/pgjdbc/pgjdbc/issues/1547) As long as peek returns some bytes do not reset the timeout, this allows us to continue checking until any async notifies are consumed [PR 1548](https://github.com/pgjdbc/pgjdbc/pull/1548)
- fix: [issue 1466](https://github.com/pgjdbc/pgjdbc/issues/1466) In logical decoding the if the backend was requesting a reply we… [PR 1467](https://github.com/pgjdbc/pgjdbc/pull/1467) 
- fix: [issue 1534](https://github.com/pgjdbc/pgjdbc/issues/1534) Proleptic java.time support [PR 1539](https://github.com/pgjdbc/pgjdbc/pull/1539)
- fix Ensure isValid() will not last more than timeout seconds [PR 1557](https://github.com/pgjdbc/pgjdbc/pull/1557)
## [42.2.6] (2019-06-19)
### Known issues
- Waffle has [dropped support](https://github.com/Waffle/waffle/releases/tag/waffle-1.9.0) for 1.6, 1.7 as such the new waffle 1.9.x is only available in jre8
- Microseconds in timestamps might be truncated when transferred in binary mode
- 24:00 time handling is not consistent [issue 1385](https://github.com/pgjdbc/pgjdbc/issues/1385)
- Unexpected packet type during stream replication [issue 1466](https://github.com/pgjdbc/pgjdbc/issues/1466)
- Driver goes missing after OSGi bundle restart [issue 1476](https://github.com/pgjdbc/pgjdbc/issues/1476)
### Changed
- Change IS_GENERATED to IS_GENERATEDCOLUMN as per spec [PR 1485](https://github.com/pgjdbc/pgjdbc/pull/1485)
- Fix missing metadata columns, and misspelled columns in PgDatabaseMetaData#getTables [PR 1323](https://github.com/pgjdbc/pgjdbc/pull/1323)

### Added
- CI tests with Java 11, and Java EA
- Support temporary replication slots in ReplicationCreateSlotBuilder [PR 1306](https://github.com/pgjdbc/pgjdbc/pull/1306)
- Support PostgreSQL 11, 12
- Return function (PostgreSQL 11) columns in PgDatabaseMetaData#getFunctionColumns
- Return information on create replication slot, now the snapshot_name is exported
  to allow a consistent snapshot in some uses cases. [PR 1335](https://github.com/pgjdbc/pgjdbc/pull/1335)

### Fixed
- Fixed async copy performance (1ms per op) in SSL mode [PR 1314](https://github.com/pgjdbc/pgjdbc/pull/1314)
- Return Double.NaN for 'NaN'::numeric [PR 1304](https://github.com/pgjdbc/pgjdbc/pull/1304)
- Performance issue in PgDatabaseMetaData#getTypeInfo with lots of types in DB [PR 1302](https://github.com/pgjdbc/pgjdbc/pull/1302)
- PGCopyInputStream#read should cap values to [0, 255], -1 [PR 1349](https://github.com/pgjdbc/pgjdbc/pull/1349)
- Fixes LocalDateTime handling of BC dates [PR 1388](https://github.com/pgjdbc/pgjdbc/pull/1388)
- Release savepoints in autosave mode to prevent out of shared memory errors at the server side [PR 1409](https://github.com/pgjdbc/pgjdbc/pull/1409)
- Fix execution with big decimal in simple query mode. [PR 1463](https://github.com/pgjdbc/pgjdbc/pull/1463)
- Fix rounding for timestamps truncated to dates before 1970 [PR 1502](https://github.com/pgjdbc/pgjdbc/pull/1502)


## [42.2.5] (2018-08-27)
### Known issues
- 1ms per async copy call [issue 1312](https://github.com/pgjdbc/pgjdbc/issues/1312)

### Changed
- `ssl=true` implies `sslmode=verify-full`, that is it requires valid server certificate [cdeeaca4](https://github.com/pgjdbc/pgjdbc/commit/cdeeaca47dc3bc6f727c79a582c9e4123099526e)

targetServerType=master has been deprecated in favour of targetServerType=primary. master
will still be accepted but not documented.
### Added
- Support for `sslmode=allow/prefer/require` [cdeeaca4](https://github.com/pgjdbc/pgjdbc/commit/cdeeaca47dc3bc6f727c79a582c9e4123099526e)

### Fixed
- Security: added server hostname verification for non-default SSL factories in `sslmode=verify-full` (CVE-2018-10936) [cdeeaca4](https://github.com/pgjdbc/pgjdbc/commit/cdeeaca47dc3bc6f727c79a582c9e4123099526e)
- Updated documentation on SSL configuration [fa032732](https://github.com/pgjdbc/pgjdbc/commit/fa032732acfe51c6e663ee646dd5c1beaa1af857)
- Updated Japanese translations [PR 1275](https://github.com/pgjdbc/pgjdbc/pull/1275)
- IndexOutOfBounds on prepared multistatement with insert values [c2885dd0](https://github.com/pgjdbc/pgjdbc/commit/c2885dd0cfc793f81e5dd3ed2300bb32476eb14a)

## [42.2.4] (2018-07-14)
### Changed
- PreparedStatement.setNull(int parameterIndex, int t, String typeName) no longer ignores the typeName
argument if it is not null [PR 1160](https://github.com/pgjdbc/pgjdbc/pull/1160)

### Fixed
- Fix treatment of SQL_TSI_YEAR, SQL_TSI_WEEK, SQL_TSI_MINUTE [PR 1250](https://github.com/pgjdbc/pgjdbc/pull/1250)
- Map integrity constraint violation to XA_RBINTEGRITY instead of XAER_RMFAIL [PR 1175](https://github.com/pgjdbc/pgjdbc/pull/1175) [f2d1352c](https://github.com/pgjdbc/pgjdbc/commit/f2d1352c2b3ea98492beb6127cd6d95039a0b92f)

## [42.2.3] (2018-07-12)
### Known issues
- SQL_TSI_YEAR is treated as hour, SQL_TSI_WEEK is treated as hour, SQL_TSI_MINUTE is treated as second

### Changed
- Reduce the severity of the error log messages when an exception is re-thrown. The error will be 
thrown to caller to be dealt with so no need to log at this verbosity by pgjdbc  [PR 1187](https://github.com/pgjdbc/pgjdbc/pull/1187)
- Deprecate Fastpath API [PR 903](https://github.com/pgjdbc/pgjdbc/pull/903)
- Support parenthesis in {oj ...} JDBC escape syntax [PR 1204](https://github.com/pgjdbc/pgjdbc/pull/1204)
- ubenchmark module moved pgjdbc/benchmarks repository due to licensing issues [PR 1215](https://github.com/pgjdbc/pgjdbc/pull/1215)
- Include section on how to submit a bug report in CONTRIBUTING.md [PR 951](https://github.com/pgjdbc/pgjdbc/pull/951)

### Fixed
- getString for PGObject-based types returned "null" string instead of null [PR 1154](https://github.com/pgjdbc/pgjdbc/pull/1154)
- Field metadata cache can be disabled via databaseMetadataCacheFields=0 [PR 1052](https://github.com/pgjdbc/pgjdbc/pull/1052)
- Properly encode special symbols in passwords in BaseDataSource [PR 1201](https://github.com/pgjdbc/pgjdbc/pull/1201)
- Adjust date, hour, minute, second when rounding nanosecond part of a timestamp [PR 1212](https://github.com/pgjdbc/pgjdbc/pull/1212)
- perf: reduce memory allocations in query cache [PR 1227](https://github.com/pgjdbc/pgjdbc/pull/1227)
- perf: reduce memory allocations in SQL parser [PR 1230](https://github.com/pgjdbc/pgjdbc/pull/1230), [PR 1233](https://github.com/pgjdbc/pgjdbc/pull/1233)
- Encode URL parameters in BaseDataSource [PR 1201](https://github.com/pgjdbc/pgjdbc/pull/1201)
- Improve JavaDoc formatting [PR 1236](https://github.com/pgjdbc/pgjdbc/pull/1236)

## [42.2.2] (2018-03-15)
### Added
- Documentation on server-side prepared statements [PR 1135](https://github.com/pgjdbc/pgjdbc/pull/1135)

### Fixed
- Avoid failure for `insert ... on conflict...update` for `reWriteBatchedInserts=true` case [PR 1130](https://github.com/pgjdbc/pgjdbc/pull/1130)
- fix: allowEncodingChanges should allow set client_encoding=... [PR 1125](https://github.com/pgjdbc/pgjdbc/pull/1125)
- Wrong data from Blob/Clob when mark/reset is used [PR 971](https://github.com/pgjdbc/pgjdbc/pull/971)
- Adjust XAException return codes for better compatibility with XA specification [PR 782](https://github.com/pgjdbc/pgjdbc/pull/782)
- Wrong results when single statement is used with different bind types[PR 1137](https://github.com/pgjdbc/pgjdbc/pull/1137)
- Support generated keys for WITH queries that miss RETURNING [PR 1138](https://github.com/pgjdbc/pgjdbc/pull/1138)
- Support generated keys when INSERT/UPDATE/DELETE keyword is followed by a comment [PR 1138](https://github.com/pgjdbc/pgjdbc/pull/1138)

## [42.2.1] (2018-01-25)
### Known issues
- client_encoding has to be UTF8 even with allowEncodingChanges=true

### Changed
- socksProxyHost is ignored in case it contains empty string [PR 1079](https://github.com/pgjdbc/pgjdbc/pull/1079)

### Fixed
- Avoid connection failure when `DateStyle` is set to `ISO` (~PgBouncer) [Issue 1080](https://github.com/pgjdbc/pgjdbc/issues/1080)
- Package scram:client classes, so SCRAM works when using a shaded jar [PR 1091](https://github.com/pgjdbc/pgjdbc/pull/1091) [1a89290e](https://github.com/pgjdbc/pgjdbc/commit/1a89290e110d5863b35e0a2ccf79e4292c1056f8)
- reWriteBatchedInserts=true causes syntax error with ON CONFLICT [Issue 1045](https://github.com/pgjdbc/pgjdbc/issues/1045) [PR 1082](https://github.com/pgjdbc/pgjdbc/pull/1082)
- Avoid failure in getPGArrayType when stringType=unspecified [PR 1036](https://github.com/pgjdbc/pgjdbc/pull/1036)
- For PostgreSQL 9.0+ return a complete list of keywords in DatabaseMetadata.getSQLKeywords() from pg_catalog.pg_get_keywords(). [PR 940](https://github.com/pgjdbc/pgjdbc/pull/940)

## [42.2.0] (2018-01-17)
### Known issues
- SCRAM does not work as scram:client library is not packaged
- client_encoding has to be UTF8 even with allowEncodingChanges=true

### Added
- Support SCRAM-SHA-256 for PostgreSQL 10 in the JDBC 4.2 version (Java 8+) using the Ongres SCRAM library. [PR 842](https://github.com/pgjdbc/pgjdbc/pull/842)
- Make SELECT INTO and CREATE TABLE AS return row counts to the client in their command tags. [Issue 958](https://github.com/pgjdbc/pgjdbc/issues/958) [PR 962](https://github.com/pgjdbc/pgjdbc/pull/962)
- Support Subject Alternative Names for SSL connections. [PR 952](https://github.com/pgjdbc/pgjdbc/pull/952)
- Support isAutoIncrement metadata for PostgreSQL 10 IDENTITY column. [PR 1004](https://github.com/pgjdbc/pgjdbc/pull/1004)
- Support for primitive arrays [PR#887](https://github.com/pgjdbc/pgjdbc/pull/887) [3e0491a](https://github.com/pgjdbc/pgjdbc/commit/3e0491ac3833800721b98e7437635cf6ab338162)
- Implement support for get/setNetworkTimeout() in connections. [PR 849](https://github.com/pgjdbc/pgjdbc/pull/849)
- Make GSS JAAS login optional, add an option "jaasLogin" [PR 922](https://github.com/pgjdbc/pgjdbc/pull/922) see [Connecting to the Database](https://jdbc.postgresql.org/documentation/head/connect.html)

### Changed
- Improve behaviour of ResultSet.getObject(int, Class). [PR 932](https://github.com/pgjdbc/pgjdbc/pull/932)
- Parse CommandComplete message using a regular expresion, allows complete catch of server returned commands for INSERT, UPDATE, DELETE, SELECT, FETCH, MOVE, COPY and future commands. [PR 962](https://github.com/pgjdbc/pgjdbc/pull/962)
- Use 'time with timezone' and 'timestamp with timezone' as is and ignore the user provided Calendars, 'time' and 'timestamp' work as earlier except "00:00:00" now maps to 1970-01-01 and "24:00:00" uses the system provided Calendar ignoring the user-provided one [PR 1053](https://github.com/pgjdbc/pgjdbc/pull/1053)
- Change behaviour of multihost connection. The new behaviour is to try all secondaries first before trying the master [PR 844](https://github.com/pgjdbc/pgjdbc/pull/844).
- Avoid reflective access to TimeZone.defaultTimeZone in Java 9+ [PR 1002](https://github.com/pgjdbc/pgjdbc/pull/1002) fixes [Issue 986](https://github.com/pgjdbc/pgjdbc/issues/986)

### Fixed
- Make warnings available as soon as they are received from the server. This is useful for long running queries, where it can be beneficial to know about a warning before the query completes. [PR 857](https://github.com/pgjdbc/pgjdbc/pull/857)
- Use 00:00:00 and 24:00:00 for LocalTime.MIN/MAX. [PR 992](https://github.com/pgjdbc/pgjdbc/pull/992)
- Now the DatabaseMetaData.getFunctions() implementation complies with the JDBC docs. [PR 918](https://github.com/pgjdbc/pgjdbc/pull/918)
- Execute autosave/rollback savepoint via simple queries always to prevent "statement S_xx not exists" when autosaving fixes [Issue #955](https://github.com/pgjdbc/pgjdbc/issues/955)
- Received resultset tuples, but no field structure for them" when bind failure happens on 5th execution of a statement [Issue 811](https://github.com/pgjdbc/pgjdbc/issues/811)

### Removed
- Drop support for the (insecure) crypt authentication method. [PR 1026](https://github.com/pgjdbc/pgjdbc/pull/1026)

### Deprecated
- Reintroduce Driver.getVersion for backward compatibility reasons, mark it as deprecated as application should not rely on it (regression since 42.0.0) [50d5dd3e](https://github.com/pgjdbc/pgjdbc/commit/50d5dd3e708a92602e04d6b4aa0822ad3f110a78)

## [42.1.4] (2017-08-01)
### Changed
- Statements with non-zero fetchSize no longer require server-side named handle. This might cause issues when using old PostgreSQL versions (pre-8.4)+fetchSize+interleaved ResultSet processing combo. [Issue 869](https://github.com/pgjdbc/pgjdbc/issues/869)

## [42.1.3] (2017-07-14)
### Fixed
- Fix NPE in PreparedStatement.executeBatch in case of empty batch (regression since 42.1.2). [PR 867](https://github.com/pgjdbc/pgjdbc/pull/867)

## [42.1.2] (2017-07-12)
### Changed
- Better logic for *returning* keyword detection. Previously, pgjdbc could be defeated by column names that contain *returning*, so pgjdbc failed to "return generated keys" as it considered statement as already having *returning* keyword [PR 824](https://github.com/pgjdbc/pgjdbc/pull/824) [201daf1d](https://github.com/pgjdbc/pgjdbc/commit/201daf1dc916bbc35e2bbec961aebfd1b1e30bfc) 
- Use server-prepared statements for batch inserts when prepareThreshold>0. Note: this enables batch to use server-prepared from the first *executeBatch()* execution (previously it waited for *prepareThreshold* *executeBatch()* calls) [abc3d9d7](https://github.com/pgjdbc/pgjdbc/commit/abc3d9d7f34a001322fbbe53f25d5e77a33a667f)

### Fixed
- Replication API: fix issue in #834 setting statusIntervalUpdate causes high CPU load. [PR 835](https://github.com/pgjdbc/pgjdbc/pull/835) [59236b74](https://github.com/pgjdbc/pgjdbc/commit/59236b74acdd400d9d91d3eb2bb07d70b15392e5)

### Regresions
- NPE in PreparedStatement.executeBatch in case of empty batch. Fixed in 42.1.3

## [42.1.1] (2017-05-05)
### Fixed
- Fix infinite dates that might be corrupted when transferred in binary for certain JREs. For instance, 5881610-07-11 instead of infinity. [1e5bf563](https://github.com/pgjdbc/pgjdbc/commit/1e5bf563f41203417281117ed20b183cd295b4e0)

## [42.1.0] (2017-05-04)
### Added
- Support fetching a REF_CURSOR using getObject [PR 809](https://github.com/pgjdbc/pgjdbc/pull/809)

### Fixed
- Fix data being truncated in setCharacterStream (bug introduced in 42.0.0) [PR 802](https://github.com/pgjdbc/pgjdbc/pull/802)
- Fix calculation of lastReceiveLSN for logical replication [PR 801](https://github.com/pgjdbc/pgjdbc/pull/801)
- Make sure org.postgresql.Driver is loaded when accessing though DataSource interface [Issue 768](https://github.com/pgjdbc/pgjdbc/issues/768)

### Regressions
- There's no 42.1.0.jre6 version due to infinity handling bug. Fixed in 42.1.1.jre6

## [42.0.0] (2017-02-20)
### Added
- Replication protocol API was added: [replication API documentation](https://jdbc.postgresql.org//documentation/head/replication.html). [PR 550](https://github.com/pgjdbc/pgjdbc/pull/550)
- java.util.logging is now used for logging: [logging documentation](https://jdbc.postgresql.org//documentation/head/logging.html). [PR 722](https://github.com/pgjdbc/pgjdbc/pull/722)
- Add support for PreparedStatement.setCharacterStream(int, Reader). [ee4c4265](https://github.com/pgjdbc/pgjdbc/commit/ee4c4265aebc1c73a1d1fabac5ba259d1fbfd1e4)

### Changed
- Version bumped to 42.0.0 to avoid version clash with PostgreSQL version and follow a better sematic versioning. [46634923](https://github.com/pgjdbc/pgjdbc/commit/466349236622c6b03bb9cd8d7f517c3ce0586751)
- Ensure executeBatch() can be used with pgbouncer. Previously pgjdbc could use server-prepared statements for batch execution even with prepareThreshold=0. [Issue 742](https://github.com/pgjdbc/pgjdbc/issues/742)
- Error position is displayed when SQL has unterminated literals, comments, etc. [Issue 688](https://github.com/pgjdbc/pgjdbc/issues/688)
- Strict handling of accepted values in getBoolean and setObject(BOOLEAN), now it follows PostgreSQL accepted values, only 1 and 0 for numeric types are accepted (previously !=0 was true). [PR 732](https://github.com/pgjdbc/pgjdbc/pull/732)
- Return correct versions and name of the driver. [PR 668](https://github.com/pgjdbc/pgjdbc/pull/668)

### Removed
- Support for PostgreSQL versions below 8.2 was dropped. [PR 661](https://github.com/pgjdbc/pgjdbc/pull/661)

### Deprecated
- Deprecated PGPoolingDataSource, instead of this class you should use a fully featured connection pool like HikariCP, vibur-dbcp, commons-dbcp, c3p0, etc. [PR 739](https://github.com/pgjdbc/pgjdbc/pull/739)

### Regressions
- Data truncated in setCharacterStream. Fixed in 42.1.0
- No suitable driver found for jdbc:postgresql when using a DataSource implementation. Fixed in 42.1.0


[42.0.0]: https://github.com/pgjdbc/pgjdbc/compare/REL9.4.1212...REL42.0.0
[42.1.0]: https://github.com/pgjdbc/pgjdbc/compare/REL42.0.0...REL42.1.0
[42.1.1]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.0...REL42.1.1
[42.1.2]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.1...REL42.1.2
[42.1.3]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.2...REL42.1.3
[42.1.4]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.3...REL42.1.4
[42.2.0]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.4...REL42.2.0
[42.2.1]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.0...REL42.2.1
[42.2.2]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.1...REL42.2.2
[42.2.3]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.2...REL42.2.3
[42.2.4]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.3...REL42.2.4
[42.2.5]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.4...REL42.2.5
[42.2.6]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.5...REL42.2.6
[42.2.7]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.6...REL42.2.7
[42.2.8]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.7...REL42.2.8
[42.2.9]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.8...REL42.2.9
[42.2.10]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.9...REL42.2.10
[42.2.11]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.10...REL42.2.11
[42.2.12]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.11...REL42.2.12
[42.2.14]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.12...HEAD
[42.2.15]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.14...REL42.2.15
[Unreleased]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.15...HEAD
