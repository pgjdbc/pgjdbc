---
title: "JDBC escapes"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 7
toc: true
---

#  JDBC escapes

The JDBC specification (like the ODBC specification) acknowledges the fact that
some vendor specific SQL may be required for certain RDBMS features. To aid
developers in writing portable JDBC applications across multiple database products,
a special escape syntax is used to specify the generic commands the developer
wants to be run. The JDBC driver translates these escape sequences into native
syntax for its specific database. For more information consult the
[Java DB Technical Documentation](http://docs.oracle.com/javadb/10.10.1.2/ref/rrefjdbc1020262.html).

The parsing of the sql statements for these escapes can be disabled using
`Statement.setEscapeProcessing(false)` .

`Connection.nativeSQL(String sql)` provides another way to have escapes processed.
It translates the given SQL to a SQL suitable for the PostgreSQL™ backend.

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

## Escape for like escape character

You can specify which escape character to use in strings comparison (with `LIKE` )
to protect wildcards characters ('%' and '_') by adding the following escape :
`{escape 'escape-character'}` . The driver supports this only at the end of the
comparison expression.

For example, you can compare string values using '|' as escape character to protect '_' :

```java
rs = stmt.executeQuery("select str2 from comparisontest where str1 like '|_abcd' {escape '|'} ");
```

##  Escape for outer joins

You can specify outer joins using the following syntax: `{oj table (LEFT|RIGHT|FULL) OUTER JOIN (table | outer-join)
ON search-condition  }`

For example :

```java
ResultSet rs = stmt.executeQuery( "select * from {oj a left outer join b on (a.i=b.i)} ");
```

##  Date-time escapes

The JDBC specification defines escapes for specifying date, time and timestamp
values which are supported by the driver.

> date
>> `{d 'yyyy-mm-dd'}` which is translated to `DATE 'yyyy-mm-dd'`

> time
>> `{t 'hh:mm:ss'}` which is translated to `TIME 'hh:mm:ss'`

> timestamp
>> `{ts 'yyyy-mm-dd hh:mm:ss.f...'}` which is translated to `TIMESTAMP 'yyyy-mm-dd hh:mm:ss.f'` <br /><br />
>> The fractional seconds (.f...) portion of the TIMESTAMP can be omitted.

##  Escaped scalar functions

The JDBC specification defines functions with an escape call syntax : `{fn function_name(arguments)}` .
The following tables show which functions are supported by the PostgreSQL™ driver.
The driver supports the nesting and the mixing of escaped functions and escaped
values. The appendix C of the JDBC specification describes the functions.

Some functions in the following tables are translated but reported as not supported
because they are duplicating or changing their order of the arguments. While this
is harmless for literal values or columns, it will cause problems when using
prepared statements. For example " `{fn right(?,?)}` " will be translated to " `substring(? from (length(?)+1-?))` ".
As you can see the translated SQL requires more parameters than before the
translation but the driver will not automatically handle this.

**Table 8.1. Supported escaped numeric functions**

|function|reported as supported|translation|comments|
|---|---|---|---|
|abs(arg1)|yes|abs(arg1)||
|acos(arg1)|yes|acos(arg1)||
|asin(arg1)|yes|asin(arg1)||
|atan(arg1)|yes|atan(arg1)||
|atan2(arg1, arg2)|yes|atan2(arg1, arg2)||
|ceiling(arg1)|yes|ceil(arg1)||
|cos(arg1)|yes|cos(arg1)||
|cot(arg1)|yes|cot(arg1)||
|degrees(arg1)|yes|degrees(arg1)||
|exp(arg1)|yes|exp(arg1)||
|floor(arg1)|yes|floor(arg1)||
|log(arg1)|yes|ln(arg1)||
|log10(arg1)|yes|log(arg1)||
|mod(arg1, arg2)|yes|mod(arg1, arg2)||
|pi(arg1)|yes|pi(arg1)||
|power(arg1, arg2)|yes|pow(arg1, arg2)||
|radians(arg1)|yes|radians(arg1)||
|rand()|yes|random()||
|rand(arg1)|yes|setseed(arg1)*0+random()|The seed is initialized with the given argument and a new random value is returned.|
|round(arg1, arg2)|yes|round(arg1, arg2)||
|sign(arg1)|yes|sign(arg1)||
|sin(arg1)|yes|sin(arg1)||
|sqrt(arg1)|yes|sqrt(arg1)||
|tan(arg1)|yes|tan(arg1)||
|truncate(arg1, arg2)|yes|trunc(arg1, arg2)||

**Table 8.2. Supported escaped string functions**

|function|reported as supported|translation|comments|
|---|---|---|---|
|ascii(arg1)|yes|ascii(arg1)||
|char(arg1)|yes|chr(arg1)||
|concat(arg1, arg2...)|yes|(arg1||arg2...)|The JDBC specification
only require the two arguments version, but supporting more arguments
was so easy...|
|insert(arg1, arg2, arg3, arg4)|no|overlay(arg1 placing arg4 from arg2 for arg3)|This function is not supported since it changes the order of the arguments which can be a problem (for prepared
statements by example).|
|lcase(arg1)|yes|lower(arg1)||
|left(arg1, arg2)|yes|substring(arg1 for arg2)||
|length(arg1)|yes|length(trim(trailing from arg1))||
|locate(arg1, arg2)|no|position(arg1 in arg2)||
|locate(arg1, arg2, arg3)|no|(arg2*sign(position(arg1 in substring(arg2 from arg3)+position(arg1 in substring(arg2 from arg3))|Not supported since the three arguments version duplicate and change the order of the arguments.|
|ltrim(arg1)|yes|trim(leading from arg1)||
|repeat(arg1, arg2)|yes|repeat(arg1, arg2)||
|replace(arg1, arg2, arg3)|yes|replace(arg1, arg2, arg3)|Only reported as supported by 7.3 and above servers.|
|right(arg1, arg2)|no|substring(arg1 from (length(arg1)+1-arg2))|Not supported since arg2 is duplicated.|
|rtrim(arg1)|yes|trim(trailing from arg1)||
|space(arg1)|yes|repeat(' ', arg1)||
|substring(arg1, arg2)|yes|substr(arg1, arg2)||
|substring(arg1, arg2, arg3)|yes|substr(arg1, arg2, arg3)||
|ucase(arg1)|yes|upper(arg1)||
|soundex(arg1)|no|soundex(arg1)|Not supported since it requires the fuzzystrmatch contrib module.|
|difference(arg1, arg2)|no|difference(arg1, arg2)|Not supported since it requires the fuzzystrmatch contrib module.|

**Table 8.3. Supported escaped date/time functions**

|function|reported as supported|translation|comments|
|---|---|---|---|
|curdate()|yes|current_date||
|curtime()|yes|current_time||
|dayname(arg1)|yes|to_char(arg1, 'Day')||
|dayofmonth(arg1)|yes|extract(day from arg1)||
|dayofweek(arg1)|yes|extract(dow from arg1)+1|We must add 1 to be in the expected 1-7 range.|
|dayofyear(arg1)|yes|extract(doy from arg1)||
|hour(arg1)|yes|extract(hour from arg1)||
|minute(arg1)|yes|extract(minute from arg1)||
|month(arg1)|yes|extract(month from arg1)||
|monthname(arg1)|yes|to_char(arg1, 'Month')||
|now()|yes|now()||
|quarter(arg1)|yes|extract(quarter from arg1)||
|second(arg1)|yes|extract(second from arg1)||
|week(arg1)|yes|extract(week from arg1)||
|year(arg1)|yes|extract(year from arg1)||
|timestampadd(argIntervalType, argCount, argTimeStamp)|yes|('(interval according to argIntervalType and
argCount)'+argTimeStamp)|an argIntervalType value of SQL_TSI_FRAC_SECOND
is not implemented since backend does not support it|
|timestampdiff(argIntervalType, argTimeStamp1, argTimeStamp2)|not|extract((interval according to argIntervalType) from argTimeStamp2-argTimeStamp1 )|only an argIntervalType value of SQL_TSI_FRAC_SECOND, SQL_TSI_FRAC_MINUTE, SQL_TSI_FRAC_HOUR or SQL_TSI_FRAC_DAY is supported|

**Table 8.4. Supported escaped misc functions**

|function|reported as supported|translation|comments|
|---|---|---|---|
|database()|yes|current_database()|Only reported as supported by 7.3 and above servers.|
|ifnull(arg1, arg2)|yes|coalesce(arg1, arg2)||
|user()|yes|user||
