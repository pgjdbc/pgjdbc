---
layout: default_docs
title: Date-time escapes
header: Chapter 8. JDBC escapes
resource: /documentation/head/media
previoustitle: Escape for outer joins
previous: outer-joins-escape.html
nexttitle: Escaped scalar functions
next: escaped-functions.html
---

The JDBC specification defines escapes for specifying date, time and timestamp
values which are supported by the driver.

> date
>> `{d 'yyyy-mm-dd'}` which is translated to `DATE 'yyyy-mm-dd'`

> time
>> `{t 'hh:mm:ss'}` which is translated to `TIME 'hh:mm:ss'`

> timestamp
>> `{ts 'yyyy-mm-dd hh:mm:ss.f...'}` which is translated to `TIMESTAMP 'yyyy-mm-dd hh:mm:ss.f'`<br /><br />
>> The fractional seconds (.f...) portion of the TIMESTAMP can be omitted.
