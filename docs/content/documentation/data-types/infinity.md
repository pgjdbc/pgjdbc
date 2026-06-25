---
title: "Timestamp Infinity"
---


The driver uses the following values to represent negative and positive infinity:

| type | Negative infinity | Positive infinity |
| ------- | ---------------------| --------------------- |
| `LocalDateTime` | `LocalDateTime.MIN` | `LocalDateTime.MAX` |
| `OffsetDateTime`| `OffsetDateTime.MIN` | `OffsetDateTime.MAX` |
| `java.sql.Timestamp`| when object's millisecond value equals `PGStatement.DATE_NEGATIVE_INFINITY`| when object's millisecond value equals `PGStatement.DATE_POSITIVE_INFINITY` |

#### ResultSet example

```java
java.sql.Timestamp ts = myResultSet.getTimestamp("mycol");

if (ts.getTime() == PGStatement.DATE_NEGATIVE_INFINITY) {
  // The value in the database is '-infinity'
}
if (ts.getTime() == PGStatement.DATE_POSITIVE_INFINITY) {
  // The value in the database is 'infinity'
}
```
