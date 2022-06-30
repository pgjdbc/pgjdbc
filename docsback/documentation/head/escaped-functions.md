---
layout: default_docs
title: Escaped scalar functions
header: Chapter 8. JDBC escapes
resource: /documentation/head/media
previoustitle: Date-time escapes
previous: escapes-datetime.html
nexttitle: Chapter 9. PostgreSQL™ Extensions to the JDBC API
next: ext.html
---

The JDBC specification defines functions with an escape call syntax : `{fn function_name(arguments)}`.
The following tables show which functions are supported by the PostgreSQL™ driver. 
The driver supports the nesting and the mixing of escaped functions and escaped
values. The appendix C of the JDBC specification describes the functions.

Some functions in the following tables are translated but reported as not supported
because they are duplicating or changing their order of the arguments. While this
is harmless for literal values or columns, it will cause problems when using
prepared statements. For example "`{fn right(?,?)}`" will be translated to "`substring(? from (length(?)+1-?))`".
As you can see the translated SQL requires more parameters than before the
translation but the driver will not automatically handle this.

<a name="escape-numeric-functions-table"></a>
**Table 8.1. Supported escaped numeric functions**

<table summary="Supported escaped numeric functions" class="CALSTABLE" border="1">
  <tr>
    <th>function</th>
    <th>reported as supported</th>
    <th>translation</th>
    <th>comments</th>
  </tr>
  <tbody>
    <tr>
      <td>abs(arg1)</td>
      <td>yes</td>
      <td>abs(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>acos(arg1)</td>
      <td>yes</td>
      <td>acos(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>asin(arg1)</td>
      <td>yes</td>
      <td>asin(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>atan(arg1)</td>
      <td>yes</td>
      <td>atan(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>atan2(arg1,arg2)</td>
      <td>yes</td>
      <td>atan2(arg1,arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>ceiling(arg1)</td>
      <td>yes</td>
      <td>ceil(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>cos(arg1)</td>
      <td>yes</td>
      <td>cos(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>cot(arg1)</td>
      <td>yes</td>
      <td>cot(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>degrees(arg1)</td>
      <td>yes</td>
      <td>degrees(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>exp(arg1)</td>
      <td>yes</td>
      <td>exp(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>floor(arg1)</td>
      <td>yes</td>
      <td>floor(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>log(arg1)</td>
      <td>yes</td>
      <td>ln(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>log10(arg1)</td>
      <td>yes</td>
      <td>log(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>mod(arg1,arg2)</td>
      <td>yes</td>
      <td>mod(arg1,arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>pi(arg1)</td>
      <td>yes</td>
      <td>pi(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>power(arg1,arg2)</td>
      <td>yes</td>
      <td>pow(arg1,arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>radians(arg1)</td>
      <td>yes</td>
      <td>radians(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>rand()</td>
      <td>yes</td>
      <td>random()</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>rand(arg1)</td>
      <td>yes</td>
      <td>setseed(arg1)*0+random()</td>
      <td>The seed is initialized with the given argument and a new random value is returned.</td>
    </tr>
    <tr>
      <td>round(arg1,arg2)</td>
      <td>yes</td>
      <td>round(arg1,arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>sign(arg1)</td>
      <td>yes</td>
      <td>sign(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>sin(arg1)</td>
      <td>yes</td>
      <td>sin(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>sqrt(arg1)</td>
      <td>yes</td>
      <td>sqrt(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>tan(arg1)</td>
      <td>yes</td>
      <td>tan(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>truncate(arg1,arg2)</td>
      <td>yes</td>
      <td>trunc(arg1,arg2)</td>
      <td>&nbsp;</td>
    </tr>
  </tbody>
</table>

<a name="escape-string-functions-table"></a>
**Table 8.2. Supported escaped string functions**

<table summary="Supported escaped string functions" class="CALSTABLE" border="1">
  <tr>
    <th>function</th>
    <th>reported as supported</th>
    <th>translation</th>
    <th>comments</th>
  </tr>
  <tbody>
    <tr>
      <td>ascii(arg1)</td>
      <td>yes</td>
      <td>ascii(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>char(arg1)</td>
      <td>yes</td>
      <td>chr(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>concat(arg1,arg2...)</td>
      <td>yes</td>
      <td>(arg1||arg2...)</td>
      <td>The JDBC specification
only require the two arguments version, but supporting more arguments
was so easy...</td>
    </tr>
    <tr>
      <td>insert(arg1,arg2,arg3,arg4)</td>
      <td>no</td>
      <td>overlay(arg1 placing arg4 from arg2 for arg3)</td>
      <td>This function is not supported since it changes
the order of the arguments which can be a problem (for prepared
statements by example).</td>
    </tr>
    <tr>
      <td>lcase(arg1)</td>
      <td>yes</td>
      <td>lower(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>left(arg1,arg2)</td>
      <td>yes</td>
      <td>substring(arg1 for arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>length(arg1)</td>
      <td>yes</td>
      <td>length(trim(trailing from arg1))</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>locate(arg1,arg2)</td>
      <td>no</td>
      <td>position(arg1 in arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>locate(arg1,arg2,arg3)</td>
      <td>no</td>
      <td>(arg2*sign(position(arg1 in substring(arg2 from
arg3)+position(arg1 in substring(arg2 from arg3))</td>
      <td>Not supported since the three arguments version
duplicate and change the order of the arguments.</td>
    </tr>
    <tr>
      <td>ltrim(arg1)</td>
      <td>yes</td>
      <td>trim(leading from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>repeat(arg1,arg2)</td>
      <td>yes</td>
      <td>repeat(arg1,arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>replace(arg1,arg2,arg3)</td>
      <td>yes</td>
      <td>replace(arg1,arg2,arg3)</td>
      <td>Only reported as supported by 7.3 and above servers.</td>
    </tr>
    <tr>
      <td>right(arg1,arg2)</td>
      <td>no</td>
      <td>substring(arg1 from (length(arg1)+1-arg2))</td>
      <td>Not supported since arg2 is duplicated.</td>
    </tr>
    <tr>
      <td>rtrim(arg1)</td>
      <td>yes</td>
      <td>trim(trailing from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>space(arg1)</td>
      <td>yes</td>
      <td>repeat(' ',arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>substring(arg1,arg2)</td>
      <td>yes</td>
      <td>substr(arg1,arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>substring(arg1,arg2,arg3)</td>
      <td>yes</td>
      <td>substr(arg1,arg2,arg3)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>ucase(arg1)</td>
      <td>yes</td>
      <td>upper(arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>soundex(arg1)</td>
      <td>no</td>
      <td>soundex(arg1)</td>
      <td>Not supported since it requires the fuzzystrmatch
contrib module.</td>
    </tr>
    <tr>
      <td>difference(arg1,arg2)</td>
      <td>no</td>
      <td>difference(arg1,arg2)</td>
      <td>Not supported since it requires the fuzzystrmatch
contrib module.</td>
    </tr>
  </tbody>
</table>

<a name="escape-datetime-functions-table"></a>
**Table 8.3. Supported escaped date/time functions**

<table summary="Supported escaped date/time functions" class="CALSTABLE" border="1">
  <tr>
    <th>function</th>
    <th>reported as supported</th>
    <th>translation</th>
    <th>comments</th>
  </tr>
  <tbody>
    <tr>
      <td>curdate()</td>
      <td>yes</td>
      <td>current_date</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>curtime()</td>
      <td>yes</td>
      <td>current_time</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>dayname(arg1)</td>
      <td>yes</td>
      <td>to_char(arg1,'Day')</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>dayofmonth(arg1)</td>
      <td>yes</td>
      <td>extract(day from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>dayofweek(arg1)</td>
      <td>yes</td>
      <td>extract(dow from arg1)+1</td>
      <td>We must add 1 to be in the expected 1-7 range.</td>
    </tr>
    <tr>
      <td>dayofyear(arg1)</td>
      <td>yes</td>
      <td>extract(doy from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>hour(arg1)</td>
      <td>yes</td>
      <td>extract(hour from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>minute(arg1)</td>
      <td>yes</td>
      <td>extract(minute from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>month(arg1)</td>
      <td>yes</td>
      <td>extract(month from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>monthname(arg1)</td>
      <td>yes</td>
      <td>to_char(arg1,'Month')</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>now()</td>
      <td>yes</td>
      <td>now()</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>quarter(arg1)</td>
      <td>yes</td>
      <td>extract(quarter from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>second(arg1)</td>
      <td>yes</td>
      <td>extract(second from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>week(arg1)</td>
      <td>yes</td>
      <td>extract(week from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>year(arg1)</td>
      <td>yes</td>
      <td>extract(year from arg1)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>timestampadd(argIntervalType,argCount,argTimeStamp)</td>
      <td>yes</td>
      <td>('(interval according to argIntervalType and
argCount)'+argTimeStamp)</td>
      <td>an argIntervalType value of SQL_TSI_FRAC_SECOND
is not implemented since backend does not support it</td>
    </tr>
    <tr>
      <td>timestampdiff(argIntervalType,argTimeStamp1,argTimeStamp2)</td>
      <td>not</td>
      <td>extract((interval according to argIntervalType) from
argTimeStamp2-argTimeStamp1 )</td>
      <td>only an argIntervalType value of SQL_TSI_FRAC_SECOND, SQL_TSI_FRAC_MINUTE, SQL_TSI_FRAC_HOUR
or SQL_TSI_FRAC_DAY is supported </td>
    </tr>
  </tbody>
</table>

<a name="escape-misc-functions-table"></a>
**Table 8.4. Supported escaped misc functions**

<table summary="Supported escaped misc functions" class="CALSTABLE" border="1">
  <tr>
    <th>function</th>
    <th>reported as supported</th>
    <th>translation</th>
    <th>comments</th>
  </tr>
  <tbody>
    <tr>
      <td>database()</td>
      <td>yes</td>
      <td>current_database()</td>
      <td>Only reported as supported by 7.3 and above servers.</td>
    </tr>
    <tr>
      <td>ifnull(arg1,arg2)</td>
      <td>yes</td>
      <td>coalesce(arg1,arg2)</td>
      <td>&nbsp;</td>
    </tr>
    <tr>
      <td>user()</td>
      <td>yes</td>
      <td>user</td>
      <td>&nbsp;</td>
    </tr>
  </tbody>
</table>
