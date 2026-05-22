---
title: "Binary data (BYTEA)"
date: 2022-06-19T22:46:55+05:30
draft: false
weight: 6
toc: false
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/binary-data/"
---

PostgreSQL® provides two distinct ways to store binary data.  Binary data can be stored in a table using the data type
BYTEA or by using the Large Object feature which stores the binary data in a separate table in a special format and refers
to that table by storing a value of type OID in your table.

In order to determine which method is appropriate you need to understand the limitations of each method. The BYTEA data
type is not well suited for storing very large amounts of binary data. While a column of type BYTEA can hold up to 1 GB
of binary data, it would require a huge amount of memory to process such a large value. The Large Object method for
storing binary data is better suited to storing very large values, but it has its own limitations. Specifically, deleting
a row that contains a Large Object reference does not delete the Large Object. Deleting the Large Object is a separate
operation that needs to be performed. Access control is also independent: GRANT/REVOKE on the table holding the OID
does not apply to the LO itself; each LO has its own ACL that has to be managed separately. For details on PostgreSQL
Large Object storage, access control, and APIs, see the
[PostgreSQL Large Objects documentation](https://www.postgresql.org/docs/current/largeobjects.html).

Version 7.2 was the first release of the JDBC driver that supports the BYTEA data type. The introduction of this functionality
in 7.2 has introduced a change in behavior as compared to previous releases. Since 7.2, the methods
`getBytes()`, `setBytes()`, `getBinaryStream()`, and `setBinaryStream()` operate on the BYTEA data type. In 7.1 and
earlier, these methods operated on the OID data type associated with Large Objects.

To use the BYTEA data type you should simply use the `getBytes()`, `setBytes()`, `getBinaryStream()`, or `setBinaryStream()` methods.

To use the Large Object functionality you can use either the `LargeObject` class provided by the PostgreSQL® JDBC driver,
or by using the `getBlob()` and `setBlob()` methods.

> **IMPORTANT**
>
> You must access Large Objects within an SQL transaction block.  You can start a transaction block by calling `setAutoCommit(false)`.

[Example 7.1, “Processing Binary Data in JDBC”](/documentation/data-types/binary-bytea/#example71processing-binary-data-in-jdbc)
contains some examples on how to process binary data using the PostgreSQL® JDBC driver.

##### Example 7.1. Processing Binary Data in JDBC

{{< review date="2026-05-22" rev="01359fa950b5f176a7cf4036c40c2532ec95392d" >}}
- PgPreparedStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 410-421
- PgPreparedStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 508-515
- PgPreparedStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 1628-1657
- PgPreparedStatement.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgPreparedStatement.java | 1692-1723
- PgResultSet.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java | 464-470
- PgResultSet.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java | 2940-2955
- PgResultSet.java | pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java | 3020-3037
- AbstractBlobClob.java | pgjdbc/src/main/java/org/postgresql/jdbc/AbstractBlobClob.java | 116-140
- BinaryStreamTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc4/BinaryStreamTest.java | 49-76
- PreparedStatementTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc2/PreparedStatementTest.java | 204-241
- BlobTest.java | pgjdbc/src/test/java/org/postgresql/test/jdbc4/BlobTest.java | 71-111
{{< /review >}}

For example, suppose you have a table containing the file names of images and you
also want to store the image in a BYTEA column:

```sql
CREATE TABLE images (imgname text, img bytea);
```

To insert an image, you would use:

```java
File file = new File("myimage.gif");
try (FileInputStream fis = new FileInputStream(file);
     PreparedStatement ps = conn.prepareStatement("INSERT INTO images VALUES (?, ?)"); ) {
    ps.setString(1, file.getName());
    ps.setBinaryStream(2, fis, (int) file.length());
    ps.executeUpdate();
}
```

Here, `setBinaryStream()` transfers bytes from a stream into the column of type BYTEA. This also could
have been done using the `setBytes()` method if the contents of the image were already in a `byte[]`.

> **NOTE**
>
> When you call a `setBinaryStream` overload with a length argument, pgJDBC uses that length when binding the value, as
> required by JDBC. The length must match the number of bytes the driver can read from the stream. For streams whose
> length is not known ahead of time, use the `setBinaryStream(int, InputStream)` overload.

Retrieving an image is even easier. (We use `PreparedStatement` here, but the `Statement` class can equally be used.)

```java
try (PreparedStatement ps = conn.prepareStatement("SELECT img FROM images WHERE imgname = ?"); ) {
    ps.setString(1,"myimage.gif");
    try (ResultSet rs = ps.executeQuery();) {
        while(rs.next()){
            byte[] imgBytes = rs.getBytes(1);
            // use the data in some way here
        }
    }
}
```

Here the binary data was retrieved as a `byte[]`. You could have used an `InputStream` object instead.

Alternatively you could be storing a very large file and want to use the `LargeObject` API to store the file:

```sql
CREATE TABLE imageslo (imgname text, imgoid oid);
```

To insert an image, you would use:

```java
// All LargeObject API calls must be within a transaction block
conn.setAutoCommit(false);

File inputFile = new File("myimage.gif");
// Now insert the row into imageslo
try (PreparedStatement ps = conn.prepareStatement("INSERT INTO imageslo VALUES (?, ?)");
     FileInputStream fis = new FileInputStream(inputFile); ) {
    ps.setString(1, inputFile.getName());
    ps.setBlob(2, fis, inputFile.length());
    ps.executeUpdate();
}

// Finally, commit the transaction.
conn.commit();
```

Retrieving the image from the Large Object:

```java
// All LargeObject API calls must be within a transaction block
conn.setAutoCommit(false);

try (PreparedStatement ps = conn.prepareStatement("SELECT imgoid FROM imageslo WHERE imgname = ?"); ) {
    ps.setString(1, "myimage.gif");
    try (ResultSet rs = ps.executeQuery(); ) {
        while (rs.next()) {
            Blob blob = rs.getBlob(1);
            // Read all data at once
            byte[] contents = blob.getBytes(1, (int) blob.length());
            // Read all data as InputStream
            try (InputStream is = blob.getBinaryStream(); ) {
                // Process the input stream. The input stream is buffered, so you don't need to
                // wrap it in a BufferedInputStream
            } finally {
                blob.free();
            }
        }
    }
}

// Finally, commit the transaction.
conn.commit();
```

Updating the contents of the Large Object:

```java
// All LargeObject API calls must be within a transaction block
conn.setAutoCommit(false);

try (PreparedStatement ps = conn.prepareStatement("SELECT imgoid FROM imageslo WHERE imgname = ?"); ) {
    ps.setString(1, "myimage.gif");
    try (ResultSet rs = ps.executeQuery(); ) {
        while (rs.next()) {
            Blob blob = rs.getBlob(1);
            try (OutputStream os = blob.setBinaryStream(1); ) {
                // Write data to the output stream. The output stream is buffered, so you don't need to
                // wrap it in a BufferedOutputStream
            } finally {
                blob.free();
            }
        }
    }
}

// Finally, commit the transaction.
conn.commit();
```
