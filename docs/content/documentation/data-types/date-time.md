---
title: "Date and time"
description: "Mapping PostgreSQL temporal types (`DATE`, `TIME`, `TIMESTAMP`, with and without time zone) to Java 8 `java.time` types via JDBC 4.2, the UTC-normalised return of `OffsetDateTime`, and why `ZonedDateTime` and `Instant` are unsupported."
date: 2026-05-13T00:00:00Z
draft: false
weight: 25
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/query/#using-java-8-date-and-time-classes/"
---

The PostgreSQL® JDBC driver implements native support for the [Java 8 Date and Time API](http://www.oracle.com/technetwork/articles/java/jf14-date-time-2125367.html) (JSR-310) using JDBC 4.2.

##### Table 5.1. Supported Java 8 Date and Time classes

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- ResultSet Java time mappings | pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java | 704-745
- PreparedStatement Java time mappings | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 1065-1074
- PreparedStatement Java time OIDs | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 1545-1564
{{< /review >}}

|PostgreSQL®|Java SE 8|
|---|---|
|DATE|LocalDate|
|TIME [ WITHOUT TIME ZONE ]|LocalTime|
|TIME WITH TIME ZONE|OffsetTime|
|TIMESTAMP [ WITHOUT TIME ZONE ]|LocalDateTime|
|TIMESTAMP WITH TIME ZONE|OffsetDateTime|

This is closely aligned with tables B-4 and B-5 of the JDBC 4.2 specification.

> **Note**
>
> `ZonedDateTime` and `Instant` are not supported. Also note that `OffsetDateTime` instances for `TIMESTAMP WITH TIME ZONE` are returned in UTC (offset 0). This is because the backend stores them as UTC.

**Example 5.2. Reading Java 8 Date and Time values using JDBC**

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- ResultSet#getObject Java time dispatch | pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java | 4048-4056
- ResultSet Java time tests | pgjdbc/src/test/java/org/postgresql/test/jdbc42/GetObject310Test.java | 168-202
- ResultSet timestamp tests | pgjdbc/src/test/java/org/postgresql/test/jdbc42/GetObject310Test.java | 338-370
{{< /review >}}

```java
Statement st = conn.createStatement();
ResultSet rs = st.executeQuery("SELECT * FROM mytable WHERE columnfoo = 500");
while (rs.next()) {
    System.out.print("Column 1 returned ");
    LocalDate localDate = rs.getObject(1, LocalDate.class);
    System.out.println(localDate);
}
rs.close();
st.close();
```

For other data types simply pass other classes to `#getObject`.

> **Note**
>
> The Java data types need to match the SQL data types in Table 5.1.

##### Example 5.3. Writing Java 8 Date and Time values using JDBC

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- PreparedStatement#setObject Java time dispatch | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 1065-1074
- PreparedStatement Java time OIDs | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 1545-1564
- PreparedStatement Java time tests | pgjdbc/src/test/java/org/postgresql/test/jdbc42/SetObject310Test.java | 226-355
- PreparedStatement time tests | pgjdbc/src/test/java/org/postgresql/test/jdbc42/SetObject310Test.java | 458-496
{{< /review >}}

```java
LocalDate localDate = LocalDate.now();
PreparedStatement st = conn.prepareStatement("INSERT INTO mytable (columnfoo) VALUES (?)");
st.setObject(1, localDate);
st.executeUpdate();
st.close();
```
