---
layout: default_docs
title: Chapter 7. Storing Binary Data
header: Chapter 7. Storing Binary Data
resource: /documentation/head/media
previoustitle: Chapter 6. Calling Stored Functions and Procedures
previous: callproc.html
nexttitle: Chapter 8. JDBC escapes
next: escapes.html
---

PostgreSQL™ provides two distinct ways to store binary data.  Binary data can be
stored in a table using the data type BYTEA or by using the Large Object feature
which stores the binary data in a separate table in a special format and refers
to that table by storing a value of type OID in your table.

In order to determine which method is appropriate you need to understand the
limitations of each method. The BYTEA data type is not well suited for storing
very large amounts of binary data. While a column of type BYTEA can hold up to
1 GB of binary data, it would require a huge amount of memory to process such a
large value. The Large Object method for storing binary data is better suited to
storing very large values, but it has its own limitations.  Specifically deleting
a row that contains a Large Object reference does not delete the Large Object.
Deleting the Large Object is a separate operation that needs to be performed.
Large Objects also have some security issues since anyone connected to the
database can view and/or modify any Large Object, even if they don't have 
permissions to view/update the row containing the Large Object reference.

Version 7.2 was the first release of the JDBC driver that supports the BYTEA
data type. The introduction of this functionality in 7.2 has introduced a change
in behavior as compared to previous releases. Since 7.2, the methods `getBytes()`,
`setBytes()`, `getBinaryStream()`, and `setBinaryStream()` operate on the BYTEA
data type. In 7.1 and earlier, these methods operated on the OID data type
associated with Large Objects. It is possible to revert the driver back to the
old 7.1 behavior by setting the property `compatible` on the `Connection` object
to the value `7.1`. More details on connection properties are available in the
section called [“Connection Parameters”](connect.html#connection-parameters).

To use the BYTEA data type you should simply use the `getBytes()`, `setBytes()`,
`getBinaryStream()`, or `setBinaryStream()` methods.

To use the Large Object functionality you can use either the `LargeObject` class
provided by the PostgreSQL™ JDBC driver, or by using the `getBLOB()` and `setBLOB()`
methods.
 
### Important

> You must access Large Objects within an SQL transaction block.  You can start a
transaction block by calling `setAutoCommit(false)`.

[Example 7.1, “Processing Binary Data in JDBC”](binary-data.html#binary-data-example)
contains some examples on how to process binary data using the PostgreSQL™ JDBC
driver.

<a name="binary-data-example"></a>
***Example 7.1. Processing Binary Data in JDBC***

For example, suppose you have a table containing the file names of images and you
also want to store the image in a BYTEA column:

```sql
CREATE TABLE images (imgname text, img bytea);
```

To insert an image, you would use:

```java
File file = new File("myimage.gif");
FileInputStream fis = new FileInputStream(file);
PreparedStatement ps = conn.prepareStatement("INSERT INTO images VALUES (?, ?)");
ps.setString(1, file.getName());
ps.setBinaryStream(2, fis, (int)file.length());
ps.executeUpdate();
ps.close();
fis.close();
```

Here, `setBinaryStream()` transfers a set number of bytes from a stream into the
column of type BYTEA. This also could have been done using the `setBytes()` method
if the contents of the image was already in a `byte[]`. 

### Note

> The length parameter to `setBinaryStream` must be correct. There is no way to
indicate that the stream is of unknown length. If you are in this situation, you
must read the stream yourself into temporary storage and determine the length.
Now with the correct length you may send the data from temporary storage on to
the driver.

Retrieving an image is even easier. (We use `PreparedStatement` here, but the
`Statement` class can equally be used.)

```java
PreparedStatement ps = conn.prepareStatement("SELECT img FROM images WHERE imgname = ?"); 
ps.setString(1, "myimage.gif"); 
ResultSet rs = ps.executeQuery(); 
while (rs.next()) 
{ 
    byte[] imgBytes = rs.getBytes(1); 
    // use the data in some way here 
} 
rs.close(); 
ps.close();
```

Here the binary data was retrieved as an `byte[]`.  You could have used a
`InputStream` object instead.

Alternatively you could be storing a very large file and want to use the
`LargeObject` API to store the file:

```sql
CREATE TABLE imageslo (imgname text, imgoid oid);
```

To insert an image, you would use:

```java
// All LargeObject API calls must be within a transaction block
conn.setAutoCommit(false);

// Get the Large Object Manager to perform operations with
LargeObjectManager lobj = conn.unwrap(org.postgresql.PGConnection.class).getLargeObjectAPI();

// Create a new large object
long oid = lobj.createLO(LargeObjectManager.READ | LargeObjectManager.WRITE);

// Open the large object for writing
LargeObject obj = lobj.open(oid, LargeObjectManager.WRITE);

// Now open the file
File file = new File("myimage.gif");
FileInputStream fis = new FileInputStream(file);

// Copy the data from the file to the large object
byte buf[] = new byte[2048];
int s, tl = 0;
while ((s = fis.read(buf, 0, 2048)) > 0)
{
    obj.write(buf, 0, s);
    tl += s;
}

// Close the large object
obj.close();

// Now insert the row into imageslo
PreparedStatement ps = conn.prepareStatement("INSERT INTO imageslo VALUES (?, ?)");
ps.setString(1, file.getName());
ps.setLong(2, oid);
ps.executeUpdate();
ps.close();
fis.close();

// Finally, commit the transaction.
conn.commit();
```

Retrieving the image from the Large Object:

```java
// All LargeObject API calls must be within a transaction block
conn.setAutoCommit(false);

// Get the Large Object Manager to perform operations with
LargeObjectManager lobj = conn.unwrap(org.postgresql.PGConnection.class).getLargeObjectAPI();

PreparedStatement ps = conn.prepareStatement("SELECT imgoid FROM imageslo WHERE imgname = ?");
ps.setString(1, "myimage.gif");
ResultSet rs = ps.executeQuery();
while (rs.next())
{
    // Open the large object for reading
    long oid = rs.getLong(1);
    LargeObject obj = lobj.open(oid, LargeObjectManager.READ);

    // Read the data
    byte buf[] = new byte[obj.size()];
    obj.read(buf, 0, obj.size());
    // Do something with the data read here

    // Close the object
    obj.close();
}
rs.close();
ps.close();

// Finally, commit the transaction.
conn.commit();
```
