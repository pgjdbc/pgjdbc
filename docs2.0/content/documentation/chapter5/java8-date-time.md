---
title: Using Java 8 Date and Time classes
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 17
menu:
  docs:
    parent: "chapter5"
    weight: 6
---

The PostgreSQL™ JDBC driver implements native support for the
[Java 8 Date and Time API](http://www.oracle.com/technetwork/articles/java/jf14-date-time-2125367.html)
(JSR-310) using JDBC 4.2.

**Table 5.1. Supported Java 8 Date and Time classes**
|PostgreSQL™|Java SE 8|
|---|---|
|DATE|LocalDate|
|TIME [ WITHOUT TIME ZONE ]|LocalTime|
|TIMESTAMP [ WITHOUT TIME ZONE ]|LocalDateTime|
|TIMESTAMP WITH TIME ZONE|OffsetDateTime|

This is closely aligned with tables B-4 and B-5 of the JDBC 4.2 specification.
Note that `ZonedDateTime`, `Instant` and
`OffsetTime / TIME WITH TIME ZONE` are not supported. Also note
that all `OffsetDateTime` instances will have be in UTC (have offset 0).
This is because the backend stores them as UTC.

**Example 5.2. Reading Java 8 Date and Time values using JDBC**

```java
Statement st = conn.createStatement();
ResultSet rs = st.executeQuery("SELECT * FROM mytable WHERE columnfoo = 500");
while (rs.next())
{
    System.out.print("Column 1 returned ");
    LocalDate localDate = rs.getObject(1, LocalDate.class));
    System.out.println(localDate);
}
rs.close();
st.close();
```

For other data types simply pass other classes to `#getObject`.
Note that the Java data types needs to match the SQL data types in table 7.1.


**Example 5.3. Writing Java 8 Date and Time values using JDBC**

```java
LocalDate localDate = LocalDate.now();
PreparedStatement st = conn.prepareStatement("INSERT INTO mytable (columnfoo) VALUES (?)");
st.setObject(1, localDate);
st.executeUpdate();
st.close();
```
