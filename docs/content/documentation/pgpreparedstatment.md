---
layout: default_docs
title: Using the PGPreparedStatement Interface
header: Chapter 5. Issuing a Query and Processing the Result
resource: media
previoustitle: Using Java 8 Date and Time classes
previous: java8-date-time.html
nexttitle: Chapter 6. Calling Stored Functions
next: callproc.html
---

PgJDBC provides a non-standard extension to `PreparedStatment` that allows the use of named 
placeholders. Two styles are supported, `named placeholders` and `native placeholders`. This means that the same parameter can be used multiple times in a SQL statement.
The following must be considered when using the extension interface `PGPreparedStatement`:

* As this is a non-standard extension support must be enabled via the Properties passed to `DriverManager.getConnection` or via `PGConnection.setPlaceholderStyle`.
* A named placeholder starts with a colon (`:`) and at least one character as defined by `Parser.isIdentifierStartChar()` 
    this may be followed by zero or more characters as defined by `Parser.isIdentifierContChar()`. 
* A native placeholder starts with a dollar (`$`) followed by at least one digit. Native parameters must form a contiguous set of integers, starting from 1. The names may appear in any order in the SQL statement.
* Parameter names in the `PGPreparedStatement` can be obtained from the method `getParameterNames()`.
* Setter methods defined in `PGPreparedStatement` can be used to set the parameter values corresponding to the parameter name.
* Values can also be assigned to parameters using the setter methods in `PreparedStatement` using the 1-based index of the parameter name as returned by `getParameterNames()`.
* PgJDBC does not allow mixing positional (`?`) and named (`:`) or native (`$`) placeholders.
* Only one value is sent to the backend for each unique name. This means that all occurrences of each placeholder name shares the same value, and cannot be set individually.

<a name="named-parameters"></a>
**Example 5.5. Using named parameters**

This example binds a value to the parameter named myParam:

```java
Properties props = new Properties();
props.put("placeholderStyle", "any"); // "named", "native" or "any" can be enabled this way.
try (Connection con = DriverManager.getConnection(url, props); ) {
    try (PreparedStatement ps = con.prepareStatement("SELECT col_a FROM mytable WHERE col_a < :myParam AND col_b > :myParam");
        PGPreparedStatement pps = ps.unwrap(PGPreparedStatement.class);
        ps.setInt("myParam", 42);
        try (ResultSet rs = ps.executeQuery(); ) {
            // ...
        }
    }
}
```

This example binds a value to a named parameter by the index of the parameter:

```java
PreparedStatement ps = 
  conn.prepareStatement("SELECT col_a FROM mytable WHERE col_a < :myParam AND col_b > :myParam");

ps.setInt(1, 42); // Note that myParam is the 0'th element in the list returned by getParameterNames()
...
```
<a name="native-parameters"></a>
**Example 5.6. Using native parameters**

This example binds a value to the parameter $1:

```java
PGPreparedStatement ps = 
  conn.prepareStatement("SELECT col_a FROM mytable WHERE col_a < $1 AND col_b > $1")
      .unwrap(PGPreparedStatement.class);

ps.setInt("$1", 42);
...
```

This example binds a value to a named parameter by the index of the parameter:

```java
PGConnection pgConn = conn.unwrap(PGConnection.class);
pgConnection.setPlaceholderStyle(PlaceholderStyles.ANY); // NAMED, NATIVE or ANY can be enabled this way.
PreparedStatement ps = 
  conn.prepareStatement("SELECT col_a FROM mytable WHERE col_a < $1 AND col_b > $1");

ps.setInt(1, 42); // Note that $1 is the 0'th element in the list returned by getParameterNames()
...
```

Note that for the example above the following would be an error, since there is only one unique name ('$1'):
```java
...
ps.setInt(2, 43);
...
```


<a name="parameter-names"></a>
**Example 5.7. Accessing parameter names**

This example access the names available to be bound in the PreparedStatement. `PGPreparedStatement.hasParameterNames()` indicates whether or not there are any names available.
The list of names can be returned by calling `PGPreparedStatement.getParameterNames()`:

```java
PGPreparedStatement ps = 
  conn.prepareStatement("SELECT col_a FROM mytable WHERE col_a < :nameA AND col_b > :nameB")
      .unwrap(PGPreparedStatement.class);

if (ps.hasParameterNames()) {
	for (String name : ps.getParameterNames()) {
        System.out.println(name); // TODO: add expected output like ":nameA" and ":nameB"
    }
} else {
	System.out.println( "No names are available" );
}
...
```
