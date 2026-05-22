---
title: "COPY (CopyManager)"
date: 2026-05-13T00:00:00Z
draft: false
weight: 10
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/server-prepare/#copymanager/"
---

`COPY` is a PostgreSQL extension to standard SQL; see the [`COPY` command reference](https://www.postgresql.org/docs/current/sql-copy.html) for the underlying SQL command. The driver exposes it through `CopyManager`, accessed via `PGConnection.getCopyAPI()`.

## Copying data in

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- PGConnection.java | pgjdbc/src/main/java/org/postgresql/PGConnection.java | 78-85
- CopyManager.java | pgjdbc/src/main/java/org/postgresql/copy/CopyManager.java | 23-53
- CopyTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/CopyTest.java | 114-137
{{< /review >}}

```java

/*
* DDL for code below
* create table copytest (stringvalue text, intvalue int, numvalue numeric(5,2));
*/
private static String[] origData =
            {"First Row\t1\t1.10\n",
                    "Second Row\t2\t-22.20\n",
                    "\\N\t\\N\t\\N\n",
                    "\t4\t444.40\n"};
private int dataRows = origData.length;
private String sql = "COPY copytest FROM STDIN";

try (Connection con = DriverManager.getConnection(url, "postgres", "somepassword")){
    PGConnection pgConnection = con.unwrap(org.postgresql.PGConnection.class);
    CopyManager copyAPI = pgConnection.getCopyAPI();
    CopyIn cp = copyAPI.copyIn(sql);

    for (String anOrigData : origData) {
        byte[] buf = anOrigData.getBytes(StandardCharsets.UTF_8);
        cp.writeToCopy(buf, 0, buf.length);
    }

    long updatedRows = cp.endCopy();
    System.err.println(String.format("copy updated %d rows", updatedRows));
}

```

## Copying data out

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- CopyManager.java | pgjdbc/src/main/java/org/postgresql/copy/CopyManager.java | 55-64
- CopyOut.java | pgjdbc/src/main/java/org/postgresql/copy/CopyOut.java | 12-31
- CopyTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/CopyTest.java | 288-306
{{< /review >}}

```java
String sql = "COPY copytest TO STDOUT";
try (Connection con = DriverManager.getConnection(url, "postgres", "somepassword")){
    PGConnection pgConnection = con.unwrap(org.postgresql.PGConnection.class);
    CopyManager copyAPI = pgConnection.getCopyAPI();
    CopyOut cp = copyAPI.copyOut(sql);
    int count = 0;
    byte[] buf;  // This is a relatively simple example. buf will contain rows from the database

    while ((buf = cp.readFromCopy()) != null) {
        count++;
    }
    long rowCount = cp.getHandledRowCount();
}
```

## Copying through streams

For the common case when the source or the destination is already a `Reader`, `InputStream`, `Writer`, or `OutputStream`, `CopyManager` provides one-call helpers that take care of the `writeToCopy` / `readFromCopy` loop. They return the number of rows the server reports for the operation.

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- copyOut overloads | pgjdbc/src/main/java/org/postgresql/copy/CopyManager.java | 77-150
- copyIn overloads | pgjdbc/src/main/java/org/postgresql/copy/CopyManager.java | 152-257
{{< /review >}}

```java
// COPY FROM STDIN: read from any Reader (overloads exist for InputStream and ByteStreamWriter)
Reader src = new StringReader("First Row\t1\t1.10\n");
long rowsIn = copyAPI.copyIn("COPY copytest FROM STDIN", src);

// COPY TO STDOUT: write into any Writer (an OutputStream overload is also available)
StringWriter dst = new StringWriter();
long rowsOut = copyAPI.copyOut("COPY copytest TO STDOUT", dst);
```
