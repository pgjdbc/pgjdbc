---
title: Date-time escapes
date: 2022-06-19T22:46:55+05:30
draft: true
weight: 3
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
