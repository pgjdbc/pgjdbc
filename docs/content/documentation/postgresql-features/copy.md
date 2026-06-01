---
title: "CopyManager"
---

The driver provides an extension for accessing `COPY`. Copy is an extension that PostreSQL provides. see [Copy](https://www.postgresql.org/docs/current/sql-copy.html)

#### Example 9.15 Copying Data in
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
        byte[] buf = anOrigData.getBytes();
        cp.writeToCopy(buf, 0, buf.length);
    }

    long updatedRows = cp.endCopy();
    long handledRowCount = cp.getHandledRowCount();
    System.err.println(String.format("copy Updated %d Rows, and handled %d rows", updatedRows, handledRowCount));

    int rowCount = getCount(con);
    System.err.println(rowCount);

}

``` 

#### Example 9.16 Copying Data out

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

More examples can be found in the [Copy Test Code](https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/test/java/org/postgresql/test/jdbc2/CopyTest.java)
