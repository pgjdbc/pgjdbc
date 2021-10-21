---
layout: default_docs
title: Chapter 8. JDBC escapes
header: Chapter 8. JDBC escapes
resource: /documentation/head/media
previoustitle: Chapter 7. Storing Binary Data
previous: binary-data.html
nexttitle: Escape for outer joins
next: outer-joins-escape.html
---

**Table of Contents**

* [Escape for like escape character](escapes.html#like-escape)
* [Escape for outer joins](outer-joins-escape.html)
* [Date-time escapes](escapes-datetime.html)
* [Escaped scalar functions](escaped-functions.html)

The JDBC specification (like the ODBC specification) acknowledges the fact that
some vendor specific SQL may be required for certain RDBMS features. To aid
developers in writing portable JDBC applications across multiple database products,
a special escape syntax is used to specify the generic commands the developer
wants to be run. The JDBC driver translates these escape sequences into native
syntax for its specific database. For more information consult the 
[Java DB Technical Documentation](http://docs.oracle.com/javadb/10.10.1.2/ref/rrefjdbc1020262.html).

The parsing of the sql statements for these escapes can be disabled using
`Statement.setEscapeProcessing(false)`. 

`Connection.nativeSQL(String sql)` provides another way to have escapes processed.
It translates the given SQL to a SQL suitable for the PostgreSQL™ backend.

<a name="escape-use-example"></a>
**Example 8.1. Using JDBC escapes**

To use the JDBC escapes, you simply write your SQL replacing date/time literal
values, outer join and functions by the JDBC escape syntax. For example :

```java
ResultSet rs = st.executeQuery("SELECT {fn week({d '2005-01-24'})}");
```

is the portable version for

```java
ResultSet rs = st.executeQuery("SELECT extract(week from DATE '2005-01-24')");
```

<a name="like-escape"></a>
# Escape for like escape character

You can specify which escape character to use in strings comparison (with `LIKE`)
to protect wildcards characters ('%' and '_') by adding the following escape :
`{escape 'escape-character'}`. The driver supports this only at the end of the
comparison expression.

For example, you can compare string values using '|' as escape character to protect '_' :

```java
rs = stmt.executeQuery("select str2 from comparisontest where str1 like '|_abcd' {escape '|'} ");
```
