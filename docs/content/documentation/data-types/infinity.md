---
title: "Date and timestamp infinity"
date: 2026-05-13T00:00:00Z
draft: false
weight: 30
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/server-prepare/#timestamp-infinity/"
---

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- Java time infinity parsing | pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java | 582-593
- OffsetDateTime infinity parsing | pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java | 644-657
- LocalDate infinity parsing | pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java | 803-814
- Java time infinity formatting | pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java | 1058-1064
- OffsetDateTime infinity formatting | pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java | 1150-1155
- LocalDateTime infinity formatting | pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java | 1219-1224
- Timestamp infinity formatting | pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java | 855-865
- JDBC 4.2 infinity read/write tests | pgjdbc/src/test/java/org/postgresql/test/jdbc42/GetObject310InfinityTest.java | 55-98
- JDBC 4.2 infinity write tests | pgjdbc/src/test/java/org/postgresql/test/jdbc42/SetObject310InfinityTest.java | 74-98
{{< /review >}}

The driver uses the following Java values to represent negative and positive infinity for date and timestamp values:

| type | Negative infinity | Positive infinity |
| ------- | ---------------------| --------------------- |
| `LocalDate` | `LocalDate.MIN` | `LocalDate.MAX` |
| `LocalDateTime` | `LocalDateTime.MIN` | `LocalDateTime.MAX` |
| `OffsetDateTime`| `OffsetDateTime.MIN` | `OffsetDateTime.MAX` |
| `java.sql.Timestamp`| when object's millisecond value equals `PGStatement.DATE_NEGATIVE_INFINITY`| when object's millisecond value equals `PGStatement.DATE_POSITIVE_INFINITY` |

#### ResultSet example

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- PGStatement infinity constants | pgjdbc/src/main/java/org/postgresql/PGStatement.java | 15-22
- Timestamp infinity parsing | pgjdbc/src/main/java/org/postgresql/jdbc/TimestampUtils.java | 437-452
{{< /review >}}

```java
java.sql.Timestamp ts = myResultSet.getTimestamp("mycol");

if (ts.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
  // The value in the database is '-infinity'
}
if (ts.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
  // The value in the database is 'infinity'
}
```
