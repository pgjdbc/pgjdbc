# Changelog
Notable changes since version 42.0.0, read the complete [History of Changes](https://jdbc.postgresql.org/documentation/changelog.html).

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/).

## [Unreleased]
### Changed

### Added

### Fixed

[42.4.1] (2022-08-01 16:24:20 -0400)
### Security
- fix: CVE-2022-31197 Fixes SQL generated in PgResultSet.refresh() to escape column identifiers so as to prevent SQL injection.
  - Previously, the column names for both key and data columns in the table were copied as-is into the generated
  SQL. This allowed a malicious table with column names that include statement terminator to be parsed and
  executed as multiple separate commands.
  - Also adds a new test class ResultSetRefreshTest to verify this change.
  - Reported by [Sho Kato](https://github.com/kato-sho)

### Changed
- chore: skip publishing pgjdbc-osgi-test to Central
- chore: bump Gradle to 7.5
- test: update JUnit to 5.8.2

### Added
- chore: added Gradle Wrapper Validation for verifying gradle-wrapper.jar
- chore: added "permissions: contents: read" for GitHub Actions to avoid unintentional modifications by the CI
- chore: support building pgjdbc with Java 17
chore: added Gradle Wrapper Validation for verifying gradle-wrapper.jar
chore: added "permissions: contents: read" for GitHub Actions to avoid unintentional modifications by the CI
chore: support building pgjdbc with Java 17
feat: synchronize statement executions (e.g. avoid deadlock when Connection.isValid is executed from concurrent threads)

### Fixed

## [42.4.0] (2022-06-09 08:14:02 -0400)
### Changed
- fix: added GROUP_STARTUP_PARAMETERS boolean property to determine whether or not to group 
startup parameters in a transaction (default=false like 42.2.x) fixes [Issue #2425](https://github.com/pgjdbc/pgjdbc/issues/2497) 
pgbouncer cannot deal with transactions in statement pooling mode [PR #2425](https://github.com/pgjdbc/pgjdbc/pull/2425)

### Fixed
- fix: queries with up to 65535 (inclusive) parameters are supported now (previous limit was 32767) 
[PR #2525](https://github.com/pgjdbc/pgjdbc/pull/2525), [Issue #1311](https://github.com/pgjdbc/pgjdbc/issues/1311)
- fix: workaround JarIndex parsing issue by using groupId/artifactId-version directory namings. 
Regression since 42.2.13. [PR #2531](https://github.com/pgjdbc/pgjdbc/pull/2531), [issue #2527](https://github.com/pgjdbc/pgjdbc/issues/2527)
- fix: use Locale.ROOT for toUpperCase() toLowerCase() calls
- doc: add Vladimir Sitnikov's PGP key
- fix: return correct base type for domain from getUDTs [PR #2520](https://github.com/pgjdbc/pgjdbc/pull/2520) [Issue #2522](https://github.com/pgjdbc/pgjdbc/issues/2522)
- perf: utcTz static and renamed to UTC_TIMEZONE [PR #2519](https://github.com/pgjdbc/pgjdbc/pull/2520)
- doc: fix release version for #2377 (it should be 42.3.6, not 42.3.5)

## [42.3.6] (2022-05-24 08:52:27 -0400)
### Changed

### Added

### Fixed
- fix: close refcursors when underlying cursor==null instead of relying on defaultRowFetchSize [PR #2377](https://github.com/pgjdbc/pgjdbc/pull/2377)

## [42.3.5] (2022-05-04 08:48:35 -0400)
### Changed
- test: polish TimestampUtilsTest
- chore: use GitHub Action concurrency feature to terminate CI jobs on fast PR pushes

### Added
- Added KEYS file to allow for verifying artifacts [PR 2499](https://github.com/pgjdbc/pgjdbc/pull/2499)

### Fixed
- perf: enable tcpNoDelay by default [PR 2495](https://github.com/pgjdbc/pgjdbc/pull/2495).
 This is a regression from 42.2.x versions where tcpNoDelay defaulted to true
- docs: fix readme.md after [PR 2495](https://github.com/pgjdbc/pgjdbc/pull/2495) [PR 2496](https://github.com/pgjdbc/pgjdbc/pull/249)
- feat: targetServerType=preferPrimary connection parameter [PR 2483](https://github.com/pgjdbc/pgjdbc/pull/2483)
- fix: revert removal of toOffsetDateTime(String timestamp)  fixes [Issue #2497](https://github.com/pgjdbc/pgjdbc/issues/2497) [PR 2501](https://github.com/pgjdbc/pgjdbc/pull/2501)
  
## [42.3.4] (2022-04-01 14:16:28 -0400)
### Changed
- fix: change name of build cache [PR 2471](https://github.com/pgjdbc/pgjdbc/pull/2471)
- feat: add support for ResultSet#getObject(OffsetTime.class) and PreparedStatement#setObject(OffsetTime.class) [PR 2467](https://github.com/pgjdbc/pgjdbc/pull/2467)
- fix: Use non-synchronized getTimeZone in TimestampUtils [PR 2451](https://github.com/pgjdbc/pgjdbc/pull/2451)
- docs: Fix CHANGELOG.md misformatted markdown headings [PR 2461](https://github.com/pgjdbc/pgjdbc/pull/2461)
- docs:  remove loggerLevel and loggerFile from docs and issues [PR 2489](https://github.com/pgjdbc/pgjdbc/pull/2489)
- feat: use direct wire format -> LocalDate conversion without resorting to java.util.Date, java.util.Calendar, 
  and default timezones [PR 2464](https://github.com/pgjdbc/pgjdbc/pull/2464) fixes Issue #2221

### Added

### Fixed
- docs: Update testing documentation [PR 2446](https://github.com/pgjdbc/pgjdbc/pull/2446)
- fix: Throw an exception if the driver cannot parse the URL instead of returning NULL fixes [Issue #2421](https://github.com/pgjdbc/pgjdbc/issues/2421)  [PR 2441](https://github.com/pgjdbc/pgjdbc/pull/2441) 
- fix: Use PGProperty instead of the property names directly [PR 2444](https://github.com/pgjdbc/pgjdbc/pull/2444)
- docs: update changelog, missing links at bottom and formatting [PR 2460](https://github.com/pgjdbc/pgjdbc/pull/2460)
- fix: Remove isDeprecated from PGProperty. It was originally intended to help produce automated docs. Fixes Issue #2479 [PR 2480](https://github.com/pgjdbc/pgjdbc/pull/2480)
- fix: change PGInterval parseISO8601Format to support fractional second [PR 2457](https://github.com/pgjdbc/pgjdbc/pull/2457)
- fix: GSS login to use TGT from keytab fixes Issue #2469 [PR 2470](https://github.com/pgjdbc/pgjdbc/pull/2470) 
- fix: More test and fix for issues discovered by [PR #2476](https://github.com/pgjdbc/pgjdbc/pull/2476) [PR #2488](https://github.com/pgjdbc/pgjdbc/pull/2488)

## [42.3.3] (2022-02-15 11:32:24 -0500)
### Changed
- fix: Removed loggerFile and loggerLevel configuration. While the properties still exist. 
  They can no longer be used to configure the driver logging. Instead use java.util.logging
  configuration mechanisms such as `logging.properties`. 

### Added

### Fixed

## [42.3.2] (2022-02-01 07:35:41 -0500)
### Security
- CVE-2022-21724 pgjdbc instantiates plugin instances based on class names provided via authenticationPluginClassName, 
sslhostnameverifier, socketFactory, sslfactory, sslpasswordcallback connection properties.
However, the driver did not verify if the class implements the expected interface before instantiating the class. This
would allow a malicious class to be instantiated that could execute arbitrary code from the JVM. Fixed in [commit](https://github.com/pgjdbc/pgjdbc/commit/f4d0ed69c0b3aae8531d83d6af4c57f22312c813)

### Changed
- perf: read in_hot_standby GUC on connection [PR #2334](https://github.com/pgjdbc/pgjdbc/pull/2334)
- test: materialized view privileges [PR #2209](https://github.com/pgjdbc/pgjdbc/pull/2209) fixes [Issue #2060](https://github.com/pgjdbc/pgjdbc/issues/2060)
- docs: add info about convenience maven project [PR #2407](https://github.com/pgjdbc/pgjdbc/pull/2407)
- docs: Document timezone reversal from POSIX to ISO [PR #2413](https://github.com/pgjdbc/pgjdbc/pull/2413)
- fix: we will ask the server if it supports GSS Encryption if gssEncryption 
is prefer or require [PR #2396](https://github.com/pgjdbc/pgjdbc/pull/2396) remove the need to have a ticket in the cache before asking the server if gss encryptions are supported
- docs: remove Java 6 and 7 references from contributing [PR #2385](https://github.com/pgjdbc/pgjdbc/pull/2385)
- style: remove Java 8 / JDBC 4.2 checks [PR #2383](https://github.com/pgjdbc/pgjdbc/pull/2383) Remove all remaining checks whether the source is lower than Java 8
or JDBC 4.2.
- fix: throw SQLException for #getBoolean BIT(>1) [PR #2386](https://github.com/pgjdbc/pgjdbc/pull/2386) Throw SQLException instead of ClassCastException when calling
CallableStatement#getBoolean(int) on BIT(>1).
- style: import java.time types in more classes [PR #2382](https://github.com/pgjdbc/pgjdbc/pull/2382) Use imports for java.time types in all remaining classes.
- style: import java.time types in TimestampUtils [PR #2380](https://github.com/pgjdbc/pgjdbc/pull/2380) Use imports for java.time types in TimestampUtils.
- refactor: Change internal constructors to pass only connection Properties
Changes internal constructors for PgConnection and related classes to only accept the connection properties object and 
remove the user and password arguments. Any locations that required those fields can retrieve them from the properties map.
- test: Fix DatabaseMetadataTest to perform mview tests only on 9.3+
- perf: read in_hot_standby GUC on connection [PR #2334](https://github.com/pgjdbc/pgjdbc/pull/2334)
- doc: improv doc around binary decoding of numeric data [#2331](https://github.com/pgjdbc/pgjdbc/pull/2331)
- Add cert key type checking to chooseClientAlias [PR #2417](https://github.com/pgjdbc/pgjdbc/pull/2417)

### Added
- feat: Add authenticationPluginClassName option to provide passwords at runtime
Adds authenticationPluginClassName connection property that allows end users to specify a class
that will provide the connection passwords at runtime. Users implementing that interface must
ensure that each invocation of the method provides a new char[] array as the contents
will be filled with zeroes by the driver after use.Call sites within the driver have been updated to use the char[] directly wherever possible.
This includes direct usage in the GSS authentication code paths that internally were already converting the String password into a char[] for internal usage.
This allows configuring a connection with a password that must be generated on the fly or periodically changes. [PR #2369](https://github.com/pgjdbc/pgjdbc/pull/2369) original issue [Issue #2102](https://github.com/pgjdbc/pgjdbc/issues/2102)
- feat: add tcpNoDelay option [PR #2341](https://github.com/pgjdbc/pgjdbc/pull/2341) fixes [Issue #2324](https://github.com/pgjdbc/pgjdbc/issues/2324)
- feat: pg_service.conf and .pgpass support (jdbc:postgresql://?service=my-service) [PR #2260](https://github.com/pgjdbc/pgjdbc/pull/2260) fixes [Issue #2278](https://github.com/pgjdbc/pgjdbc/issues/2278)

### Fixed
- Use local TimestampUtil in PgStatement and PgResultset for thread safety [PR #2291](https://github.com/pgjdbc/pgjdbc/pull/2291)
  fixes [Issue #921](https://github.com/pgjdbc/pgjdbc/issues/921) synchronize modification of shared calendar
- fix: PgObject isNull() was reporting the opposite fixes [Issue #2411](https://github.com/pgjdbc/pgjdbc/issues/2411) [PR #2414](https://github.com/pgjdbc/pgjdbc/pull/2414)
- fix: default file name is ".pg_service.conf" on Windows (not "pg_service.conf") [PR #2398](https://github.com/pgjdbc/pgjdbc/pull/2398) fixes [Issue #2278](https://github.com/pgjdbc/pgjdbc/issues/2278)
- test: Fix RefCursorFetchTest on older platforms
- fix: do not close refcursor after reading if fetchsize has been set fixes [Issue #2227](https://github.com/pgjdbc/pgjdbc/issues/2227) [PR #2371](https://github.com/pgjdbc/pgjdbc/pull/2371)
- fix: rework gss authentication to use the principal name to get the credentials fixes [Issue #2235](https://github.com/pgjdbc/pgjdbc/issues/2235) [PR #2352](https://github.com/pgjdbc/pgjdbc/pull/2352)
- fix: return getIndexInfo metadata columns in UPPER CASE [PR #2368](https://github.com/pgjdbc/pgjdbc/pull/2368)
- fix: Connection leak in ConnectionFactoryImpl#tryConnect [PR #2350](https://github.com/pgjdbc/pgjdbc/pull/2350) [Issue #2351](https://github.com/pgjdbc/pgjdbc/issues/2351)
- fix: Fix For IS_AUTOGENERATED Flag [PR #2348](https://github.com/pgjdbc/pgjdbc/pull/2348)
- fix: parsing service file tests for windows [PR #2347](https://github.com/pgjdbc/pgjdbc/pull/2347)
- fix: The spec says that calling close() on a closed connection is a noop. [PR #2345](https://github.com/pgjdbc/pgjdbc/pull/2345) fixes [Issue #2300](https://github.com/pgjdbc/pgjdbc/issues/2300)
- fix: add microsecond precision to getTimestamp() called on sql TIME(6) Currently, "when fetching a value of type TIME(6) through
resultSet.getTimestamp() only ms precision is retained, the microsecond fractional digits are lost." This change will retain the microsecond
precision when .getTimestamp() is called on TIME(6). [PR #2181](https://github.com/pgjdbc/pgjdbc/pull/2181) Closes [Issue #1537](https://github.com/pgjdbc/pgjdbc/issues/1537)
- test: materialized view privileges [PR #2209](https://github.com/pgjdbc/pgjdbc/pull/2209) add and drop a materialized view
Add to TestUtil and also to DatabaseMetaData setup and teardown fixes [Issue #2060](https://github.com/pgjdbc/pgjdbc/issues/2060)
- fix: typo in connect.md [PR #2338](https://github.com/pgjdbc/pgjdbc/pull/2238) `OutOfMemoryException` => `OutOfMemoryError`
- fix: use local TimestampUtil in PgStatement and PgResultset for thread
safety TimestampUtil is not thread safe. It raises exceptions when multiple threads use ResultSets of one connection. [PR #2291](https://github.com/pgjdbc/pgjdbc/pull/2291) 
fixes [Issue #921](https://github.com/pgjdbc/pgjdbc/issues/921)
If PgStatement and PgResultSet use their own TimestampUtil no synchronize is needed.
- fix: typo in CONTRIBUTING.md [PR #2332](https://github.com/pgjdbc/pgjdbc/pull/2332) seccion => section
## [42.3.1] (2021-10-29)
### Changed
- improv: Arrays in Object[] [PR 2330](https://github.com/pgjdbc/pgjdbc/pull/2330) when an Object[] contains other arrays, treat as though it were a
multi-dimensional array the one exception is byte[], which is not supported.
- improv: Use jre utf-8 decoding [PR 2317](https://github.com/pgjdbc/pgjdbc/pull/2317) Remove use of custom utf-8 decoding.
- perf: improve performance of bytea string decoding [PR 2320](https://github.com/pgjdbc/pgjdbc/pull/2320)
improve the parsing of bytea hex encoded string by making a lookup table for each of the valid ascii code points to the 4 bit numeric value
- feat: intern/canonicalize common strings [PR 2234](https://github.com/pgjdbc/pgjdbc/pull/2234)
### Added

### Fixed
- numeric binary decode for even 10 thousands [PR #2327](https://github.com/pgjdbc/pgjdbc/pull/2327) fixes  [Issue 2326](https://github.com/pgjdbc/pgjdbc/issues/2326)
binary numeric values which represented integers multiples of 10,000 from 10,000-9,990,000 were not decoded correctly
- [typo] typo in certdir/README.md [PR #2309](https://github.com/pgjdbc/pgjdbc/pull/2309) certificatess => certificates
- [typo] typo in TimestampUtils.java [PR #2314](https://github.com/pgjdbc/pgjdbc/pull/2314) Change `Greagorian` to `Gregorian`.
- remove check for negative pid in cancel request. Apparently pgbouncer can send one fixes [Issue 2317](https://github.com/pgjdbc/pgjdbc/issues/2317) [PR #2319](https://github.com/pgjdbc/pgjdbc/pull/2319)

## [42.3.0] (2021-10-18)
### Changed
- No longer build for Java 6 or Java 7
- If assumeMinServerVersion is not defined and server is at least 9.0, group startup statements into a single transaction PR [#1977](https://github.com/pgjdbc/pgjdbc/pull/1977)

### Added
- Support for pg_service.conf file and jdbc URL syntax: "jdbc:postgresql://?service=service1".
  Resource can be provided using 1) property "-Dorg.postgresql.pgservicefile=file1" 2) environment variable PGSERVICEFILE=file2 3) default location "$HOME/.pg_service.conf" 4) environment variable PGSYSCONFDIR=dir1 looks for file "dir1/pg_service.conf".
- Support for .pgpass file. Resource can be provided using 1) property "-Dorg.postgresql.pgpassfile=file1" 2) environment variable PGPASSFILE=file2 3) default location "$HOME/.pgpass"

### Fixed
- Rework OSGi bundle activator so it does not rely on exception message to check DataSourceFactory presence PR [#507](https://github.com/pgjdbc/pgjdbc/pull/507)
- Fix database metadata getFunctions() and getProcedures() to ignore search_path when no schema pattern is specified [PR #2174](https://github.com/pgjdbc/pgjdbc/pull/2174)
- Fix refreshRow made the row readOnly. [PR #2195](https://github.com/pgjdbc/pgjdbc/pull/2195 Fixes [Issue #2193](https://github.com/pgjdbc/pgjdbc/issues/2193)
- Fix do not add double quotes to identifiers already double quoted [PR #2224](https://github.com/pgjdbc/pgjdbc/pull/2224) Fixes [Issue #2223](https://github.com/pgjdbc/pgjdbc/issues/2223)
  Add a property `QUOTE_RETURNING_IDENTIFIERS` which determines if we put double quotes
  around identifiers that are provided in the returning array.
- Fix Provide useful error message for empty or missing passwords for SCRAM auth [PR #2290](https://github.com/pgjdbc/pgjdbc/pull/2290) fixes [Issue #2288](https://github.com/pgjdbc/pgjdbc/issues/2288)

## [42.2.24] (2021-09-23)
### Fixed
- Fix startup regressions caused by [PR #1949](https://github.com/pgjdbc/pgjdbc/pull/1949). Instead of checking all types by OID, we can return types for well known types [PR #2257](https://github.com/pgjdbc/pgjdbc/pull/2257)
- Backport [PR #2148](https://github.com/pgjdbc/pgjdbc/pull/2148)
  Avoid leaking server error details through BatchUpdateException when logServerErrorDetail [PR #2254](https://github.com/pgjdbc/pgjdbc/pull/2254)
- Backpatch [PR #2247](https://github.com/pgjdbc/pgjdbc/pull/2247)
  QueryExecutorImpl.receiveFastpathResult did not properly handle ParameterStatus messages.
  This in turn caused failures for some LargeObjectManager operations. Closes [Issue #2237](https://github.com/pgjdbc/pgjdbc/issues/2237)
  Fixed by adding the missing code path, based on the existing handling in processResults. [PR #2253](https://github.com/pgjdbc/pgjdbc/pull/2253)
- Backpatch [PR #2242](https://github.com/pgjdbc/pgjdbc/pull/2242) PgDatabaseMetaData.getIndexInfo() cast operands to smallint  [PR#2253](https://github.com/pgjdbc/pgjdbc/pull/2253)
  It is possible to break method PgDatabaseMetaData.getIndexInfo() by adding certain custom operators. This PR fixes it.
- Backpatching [PR #2251](https://github.com/pgjdbc/pgjdbc/pull/2251) into 42.2 Clean up open connections to fix test failures on omni and appveyor
  use older syntax for COMMENT ON FUNCTION with explicit no-arg parameter parentheses as it is required on server versions before v10.
  Handle cleanup of connection creation in StatementTest, handle cleanup of privileged connection in DatabaseMetaDataTest
- Backpatch [PR #2245](https://github.com/pgjdbc/pgjdbc/pull/2245) fixes case where duplicate tables are returned if there are duplicate descriptions oids are not guaranteed to be unique in the catalog [PR #2248](https://github.com/pgjdbc/pgjdbc/pull/2248)
- Change to updatable result set to use correctly primary or unique keys [PR #2228](https://github.com/pgjdbc/pgjdbc/pull/2228)
    fixes issues introduced in [PR #2199](https://github.com/pgjdbc/pgjdbc/pull/2199) closes [Issue #2196](https://github.com/pgjdbc/pgjdbc/issues/2196)
- Fix NPE calling getTypeInfo when alias is null [PR #2220](https://github.com/pgjdbc/pgjdbc/pull/2220)
- Backpatch [PR #2217](https://github.com/pgjdbc/pgjdbc/pull/2217) to fix [Issue #2215](https://github.com/pgjdbc/pgjdbc/issues/2215). OIDs are unsigned integers and were not being handled correctly when they exceeded the size of signed integers


## [42.2.23] (2021-07-06)
### Changed
- Renewed the SSL keys for testing

### Fixed
- getColumnPrecision for Numeric when scale and precision not specified now returns 0 instead of 131089 fixes: Issue #2188
- Calling refreshRow on an updateable resultset made the row readOnly. Fixes Issue #2193
- results should be updateable if there is a unique index available PR#2199 Fixes Issue #2196
- Rework sql type gathering to use OID instead of typname.
  This does not have the issue of name shadowing / qual-names, and has the added benefit of fixing #1948.


## [42.2.22] (2021-06-16)
### Fixed
- Regression caused by https://github.com/pgjdbc/pgjdbc/commit/4fa2d5bc1ed8c0086a3a197fc1c28f7173d53cac. Unfortunately
  due to the blocking nature of the driver and issues with seeing if there is a byte available on a blocking stream when it is encrypted
  this introduces unacceptable delays in returning from peek(). At this time there is no simple solution to this.


## [42.2.21] (2021-06-10)
### Changed
- Update docs to reflect deprecated DataSource API setServerName backpatch [PR#2057](https://github.com/pgjdbc/pgjdbc/pull/2057) [PR #2105](https://github.com/pgjdbc/pgjdbc/pull/2105)

### Fixed
- make sure the table has defined primary keys when using updateable resultset backpatch [PR#2101](https://github.com/pgjdbc/pgjdbc/pull/2101) fixes [Issue 1975](https://github.com/pgjdbc/pgjdbc/issues/1975) [PR #2106](https://github.com/pgjdbc/pgjdbc/pull/2106)
- backpatch [PR #2143](https://github.com/pgjdbc/pgjdbc/pull/2143) read notifies or errors that come in asynchronously after the ready for query [PR #2168](https://github.com/pgjdbc/pgjdbc/pull/2168)
- backpatch [PR #507](https://github.com/pgjdbc/pgjdbc/pull/507) which reworks OSGI bundle activator fixes [ISSUE #2133](https://github.com/pgjdbc/pgjdbc/issues/2133)
- Fix database metadata getFunctions() and getProcedures() to ignore search_path when no schema pattern is specified. backpatch [PR #2174](https://github.com/pgjdbc/pgjdbc/pull/2174)
  fixes [Issue 2173](https://github.com/pgjdbc/pgjdbc/issues/2173)


## [42.2.20] (2021-04-19)
### Fixed
- Partitioned indexes were not found fixes [#2078](https://github.com/pgjdbc/pgjdbc/issues/2078) PR [#2087](https://github.com/pgjdbc/pgjdbc/pull/2087)
- isValid() timeout should not be blocked [#1943](https://github.com/pgjdbc/pgjdbc/pull/1943) Cherry-picked [#2076](https://github.com/pgjdbc/pgjdbc/pull/2076)
  The usage of `setQueryTimeout();` with the same value as the `setNetworkTimeout();` is blocking the current transaction timeout.
  The timeouts are blocking each other with this approach.
- DatabaseMetaData.getTables returns columns in UPPER case as per the spec [PR #2092](https://github.com/pgjdbc/pgjdbc/pull/2092) fixes [Issue #830](https://github.com/pgjdbc/pgjdbc/issues/830)


## [42.2.19] (2021-02-18)
**Notable Changes**
- Now the driver uses SASLprep normalization for SCRAM authentication fixing some issues with spaces in passwords.
- If closeOnCompletion is called on an existing statement and the statement is executed a second time it will fail.

### Changed
- Perf: avoid duplicate PGStream#changeSocket calls
- Fix: Actually close unclosed results. Previously was not closing the first unclosed result fixes #1903 (#1905).
There is a small behaviour change here as a result. If closeOnCompletion is called on an existing statement and the statement
is executed a second time it will fail.

### Added
- Verify code via forbidden-apis (jdk-internal and jdk-non-portable signatures) [PR #2012](https://github.com/pgjdbc/pgjdbc/pull/2012)

### Fixed
- Fix Binary transfer for numeric fixes #1935
- Fix Allow specifying binaryTransferEnable even for those types that are not enabled by default
- Fix: properly set cancel socket timeout (#2044)
- Fix "Required class information missing" when old org.jboss:jandex parses pgjdbc classes [issue 2008][https://github.com/pgjdbc/pgjdbc/issues/2008]
- Fix PGCopyInputStream returning the last row twice when reading with CopyOut API [issue 2016][https://github.com/pgjdbc/pgjdbc/issues/2016]
- Fix Connection.isValid() to not wait longer than existing network timeout [PR #2040](https://github.com/pgjdbc/pgjdbc/pull/2040)
- Fix Passwords with spaces (ASCII and non-ASCII) now work with SCRAM authentication (driver now uses SASLprep normalization) [PR #2052](https://github.com/pgjdbc/pgjdbc/pull/2052)
- Fix DatabaseMetaData.getTablePrivileges() to include views, materialized views, and foreign tables [PR #2049](https://github.com/pgjdbc/pgjdbc/pull/2049)
- Fix Resolve ParseError in PGtokenizer fixes #2050
- Fix return metadata privileges for views and foreign tables


## [42.2.18] (2020-10-15)
### Fixed
- Unfortunately changing the default of gssEncMode to ALLOW was not enough. The GSSEncMode Enum was not changed as well
fixed in #1920


## [42.2.17] (2020-10-09)
### Changed
- Change default of gssEncMode to ALLOW. PostgreSQL can deal with PREFER but there are cloud providers that did not implement the protocol properly. Libpq gets around this by checking for a GSS credential cache before attempting the connection. This is possible in JDK 8 and up, but not JDK6, or JDK7 fixes Issue #1868 [PR #1913](https://github.com/pgjdbc/pgjdbc/pull/1913)

### Added
- Add smallserial metadata [PR #899(https://github.com/pgjdbc/pgjdbc/pull/899)

### Fixed
- Avoid NullPointerException when receiving PGbox, PGcircle, PGline, PGlseg, PGpath, PGpoint, PGpolygon, and PGmoney [PR 1873] (https://github.com/pgjdbc/pgjdbc/pull/1873).
- The driver returns enum and jsonb arrays elements as String objects (like in 42.2.14 and earlier versions) [PR 1879](https://github.com/pgjdbc/pgjdbc/pull/1879).
- PgTokenizer was ignoring last empty token [PR #1882](https://github.com/pgjdbc/pgjdbc/pull/1882)
- Remove osgi from karaf fixes Issue #1891 [PR #1902](https://github.com/pgjdbc/pgjdbc/pull/1902)
- Handle nulls when the following classes are used: PGbox, PGcircle, PGline, PGlseg, PGpath, PGpoint, PGpolygon, and PGmoney.

## [42.2.16] (2020-08-20)
### Known issues
- The driver returns enum and jsonb arrays elements are returned as PGobject instances (fixed in 42.2.17)

### Fixed
- Arrays sent in binary format are now sent as 1 based. This was a regression for multi-dimensional arrays as well as text/varchar, oid and bytea arrays.
  Since 42.2.0 single dimensional arrays were stored 0 based. They are now sent 1 based which is the SQL standard, and the default
  for Postgres when sent as strings such as '{1,2,3}'. Fixes [issue 1860](https://github.com/pgjdbc/pgjdbc/issues/1860) in [PR 1863](https://github.com/pgjdbc/pgjdbc/pull/1863).

## [42.2.15] (2020-08-14)
### Known issues
- The driver returns enum and jsonb arrays elements are returned as PGobject instances (fixed in 42.2.17)

### Changed
- Rename source distribution archive to `postgresql-$version-jdbc-src.tar.gz`, and add top-level archive folder [ba017507](https://github.com/pgjdbc/pgjdbc/commit/ba0175072ee9c751c1496d2fe170f4af7256f1a5)
- Add the ability to connect with a GSSAPI encrypted connection. As of PostgreSQL version 12 GSSAPI encrypted connections
are possible. Now the driver will attempt to connect to the server with a GSSAPI encrypted connection. If that fails then
attempt an SSL connection, finally falling back to a plain text connection. All of this is controlled using both the gssEncMode
and sslMode parameters which, in concert with pg_hba.conf, determine if a particular mode is allowed and or required. [PR 1821](https://github.com/pgjdbc/pgjdbc/pull/1821) [ad921b9e](https://github.com/pgjdbc/pgjdbc/commit/ad921b9e3563b28b9a03b1e2dfaad0e34efc02f1)
- Source release archive shades dependencies (scram) by default. It affects only postgresql-version-src.tar.gz release artifact [f0301eb9](https://github.com/pgjdbc/pgjdbc/commit/f0301eb901f880059b00b0fb0a3ee93ef7d749a8)
- Refactor decoding arrays [PR 1194](https://github.com/pgjdbc/pgjdbc/pull/1194)

### Added
- Verify nullness with CheckerFramework [6e524ae5](https://github.com/pgjdbc/pgjdbc/commit/6e524ae51cee67b25426c09a7083465c820c0a0d)

### Fixed
- Avoid preparedStatement leak when using updateable ResultSet via insert/update/refreshRow [PR 1815](https://github.com/pgjdbc/pgjdbc/pull/1815) [9a0d2b18](https://github.com/pgjdbc/pgjdbc/commit/9a0d2b18a81c7ec5974d4caf2ff2d218312da25f)
- Change order of checks for oid vs primary keys. OID's have been deprecated. [PR 1613](https://github.com/pgjdbc/pgjdbc/pull/1613)
- Close certificate file stream. [PR 1837](https://github.com/pgjdbc/pgjdbc/pull/1837)
- Make sure socketTimeout is enforced [PR 1831](https://github.com/pgjdbc/pgjdbc/pull/1831)
- Assume PKCS-8 SSL key format by default [PR 1819](https://github.com/pgjdbc/pgjdbc/pull/1819)
- Preserve unquoted unicode whitespace in array literals [PR 1266](https://github.com/pgjdbc/pgjdbc/pull/1266)

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
- Parse CommandComplete message using a regular expression, allows complete catch of server returned commands for INSERT, UPDATE, DELETE, SELECT, FETCH, MOVE, COPY and future commands. [PR 962](https://github.com/pgjdbc/pgjdbc/pull/962)
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

### Regressions
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
[42.2.13]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.12...REL42.2.13
[42.2.14]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.13...REL42.2.14
[42.2.15]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.14...REL42.2.15
[42.2.16]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.15...REL42.2.16
[42.2.17]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.16...REL42.2.17
[42.2.18]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.17...REL42.2.18
[42.2.19]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.18...REL42.2.19
[42.2.20]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.19...REL42.2.20
[42.2.21]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.20...REL42.2.21
[42.2.22]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.21...REL42.2.22
[42.2.23]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.22...REL42.2.23
[42.2.24]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.23...REL42.2.24
[42.2.25]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.24...REL42.2.25
[42.3.0]: https://github.com/pgjdbc/pgjdbc/compare/REL42.2.24...REL42.3.0
[42.3.1]: https://github.com/pgjdbc/pgjdbc/compare/REL42.3.0...REL42.3.1
[42.3.2]: https://github.com/pgjdbc/pgjdbc/compare/REL42.3.1...REL42.3.2
[42.3.3]: https://github.com/pgjdbc/pgjdbc/compare/REL42.3.2...REL42.3.3
[42.3.4]: https://github.com/pgjdbc/pgjdbc/compare/REL42.3.3...REL42.3.4
[42.3.4]: https://github.com/pgjdbc/pgjdbc/compare/REL42.3.4...REL42.3.5
[42.3.5]: https://github.com/pgjdbc/pgjdbc/compare/REL42.3.5...REL42.3.6
[42.3.6]: https://github.com/pgjdbc/pgjdbc/compare/REL42.3.6...REL42.4.0
[42.4.0]: https://github.com/pgjdbc/pgjdbc/compare/REL42.4.0...REL42.4.1
[Unreleased]: https://github.com/pgjdbc/pgjdbc/compare/REL42.4.1...HEAD
