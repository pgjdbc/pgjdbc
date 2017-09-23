# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [42.1.4] - 2017-08-01
### Changed
- Statements with non-zero fetchSize no longer require server-side named handle. This might cause issues when using old PostgreSQL versions (pre-8.4)+fetchSize+interleaved ResultSet processing combo. See [issue 869](https://github.com/pgjdbc/pgjdbc/issues/869)

## [42.1.3] - 2017-07-14
### Fixed
- Fix NPE in PreparedStatement.executeBatch in case of empty batch (regression since 42.1.2) PR#867

## [42.1.2] - 2017-07-12
### Changed
- Better logic for *returning* keyword detection. Previously, pgjdbc could be defeated by column names that contain *returning*, so pgjdbc failed to "return generated keys" as it considered statement as already having *returning* keyword [PR#824](https://github.com/pgjdbc/pgjdbc/pull/824) [201daf1d](https://github.com/pgjdbc/pgjdbc/commit/201daf1dc916bbc35e2bbec961aebfd1b1e30bfc) 
- Use server-prepared statements for batch inserts when prepareThreshold>0. Note: this enables batch to use server-prepared from the first *executeBatch()* execution (previously it waited for *prepareThreshold* *executeBatch()* calls) [abc3d9d7](https://github.com/pgjdbc/pgjdbc/commit/abc3d9d7f34a001322fbbe53f25d5e77a33a667f)

### Fixed
- Replication API: fix issue in #834 setting statusIntervalUpdate causes high CPU load [PR#835](https://github.com/pgjdbc/pgjdbc/pull/835) [59236b74](https://github.com/pgjdbc/pgjdbc/commit/59236b74acdd400d9d91d3eb2bb07d70b15392e5)

## [42.1.1] - 2017-05-05
### Fixed
- Fix infinite dates that might be corrupted when transferred in binary for certain JREs. For instance, 5881610-07-11 instead of infinity.

## [42.1.0] - 2017-05-04
### Added
- Support fetching a REF_CURSOR using getObject [PR#809](https://github.com/pgjdbc/pgjdbc/pull/809)

### Fixed
- Fix data being truncated in setCharacterStream (bug introduced in 42.0.0) [PR#802](https://github.com/pgjdbc/pgjdbc/pull/802)
* Fix calculation of lastReceiveLSN for logical replication [PR#801](https://github.com/pgjdbc/pgjdbc/pull/801)
* Make sure org.postgresql.Driver is loaded when accessing though DataSource interface [#768](https://github.com/pgjdbc/pgjdbc/issues/768)

### Regresions
- There's no 42.1.0.jre6 version due to infinity handling bug. Fixed in 42.1.1.jre6

## [42.0.0] - 2017-02-20
### Added
- Replication protocol API was added: [replication API documentation](https://jdbc.postgresql.org//documentation/head/replication.html), [GitHub PR 550](https://github.com/pgjdbc/pgjdbc/pull/550)
- java.util.logging is now used for logging: [logging documentation](https://jdbc.postgresql.org//documentation/head/logging.html).
- Add support for PreparedStatement.setCharacterStream(int, Reader).

### Changed
- Version bumped to 42.0.0 to avoid version clash with PostgreSQL version and follow a better sematic versioning.
- Ensure executeBatch() can be used with pgbouncer. Previously pgjdbc could use server-prepared statements for batch execution even with prepareThreshold=0 (see [issue 742](https://github.com/pgjdbc/pgjdbc/issues/742))
- Error position is displayed when SQL has unterminated literals, comments, etc (see [issue 688](https://github.com/pgjdbc/pgjdbc/issues/688)).
- Strict handling of accepted values in getBoolean and setObject(BOOLEAN), now it follows PostgreSQL accepted values, only 1 and 0 for numeric types are acepted (previusly !=0 was true).
- Return correct versions and name of the driver.

### Removed
- Support for PostgreSQL versions below 8.2 was dropped.

### Deprecated
- Deprecated PGPoolingDataSource, instead of this class you should use a fully featured connection pool like HikariCP, vibur-dbcp, commons-dbcp, c3p0, etc.

### Regresions
- Data truncated in setCharacterStream. Fixed in 42.1.0


Read the [History of Changes](https://jdbc.postgresql.org/documentation/changelog.html#introduction) for reference of previous versions.

[Unreleased]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.4...HEAD
[42.1.4]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.3...REL42.1.4
[42.1.3]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.2...REL42.1.3
[42.1.2]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.1...REL42.1.2
[42.1.1]: https://github.com/pgjdbc/pgjdbc/compare/REL42.1.0...REL42.1.1
[42.1.0]: https://github.com/pgjdbc/pgjdbc/compare/REL42.0.0...REL42.1.0
[42.0.0]: https://github.com/pgjdbc/pgjdbc/compare/REL9.4.1212...REL42.0.0
