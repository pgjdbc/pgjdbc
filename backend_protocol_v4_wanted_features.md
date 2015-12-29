# PostgreSQL backend protocol: wanted features

Current protocol is documented here: http://www.postgresql.org/docs/9.4/static/protocol.html

It turns out it lacks certain features, thus it makes clients more complex, slower, etc.

Here's the list of features that often appear in discussions.

## Features

### Binary transfer vs exact data type

Current protocol supports text and binary transfer.
It turns out in text mode backend does not need to know the exact data type. In most cases it can
easily deduce the data type. Binary mode is typically faster, however when consuming binary,
backend assumes the data type is exact and it does not consider casts.

It would be nice to have an ability to pass a value in binary form (for efficiency) yet
make backend deduce proper data type for it.


Kevin Wooten: my biggest request is always to treat binary types as if the client “just knows” how
to handle them. There are numerous cases with the text format where the server will coerce columns
to the most correct type and it will not do this for binary requests; it just spits out a complaint
that you’ve got the wrong type.

That, and being able to switch to “prefer binary” mode in the protocol. So when I make an non-bound
request I can get the results back in binary. Currently you can only get them in text format.
This has a couple of implications. First, speed, you always have to bind before querying to get
binary results. Second is multiple SQL statements in a single request, which you
cannot do in bound requests.

### Non-trivial semantics of numerics in text mode

In text mode, numerics and money types are transferred with unknown decimal separator.
This makes it hard to decode the value as it is locale-dependent.

See: https://github.com/pgjdbc/pgjdbc/pull/439

### Lack of `prepared statement invalidated` messages from backend

Server-prepared statement might become invalid due to table structure change, column type change,
etc.
It results in "your prepared statement is no longer valid". This is no fun.

See: https://github.com/pgjdbc/pgjdbc/pull/451

## Brain dumps

### Álvaro Hernández

- Uniform headers (type byte). Some messages (startup, ssl, etc) don't have the type byte marker.
This is annoying.
- Byte type re-use. The type byte is re-used across senders and receivers. It is unambiguous,
but it is still very weird.
- Query pipelining. There has been much discussion about it. I don't have a clear conclusion other
than it's somehow possible, but requires driver support (keep track of sent/received messages
and errors). This could be easily handled with protocol support, like using a unique id for every
message and including in the reply that id.
- Cluster support. Real production environments are clusters (sets of PostgreSQL servers
using any kind of replication). Protocol should be concerned with this, specially if some HA
mechanisms are built into the protocol.
- Server metadata without authentication. Some messages should be able to be exchanged
to get information about servers (like version, read/write state and probably others) without
having to authenticate and involve in lengthy processes.
- No out-of-band messages (like query cancellation)
- Simplified design (current is too complex and with too many messages)
- Specified in a formal syntax (I mean not English, a formal language for specifying it) and
with a TCK (Test Compatibility Kit)
- Allow some controlled form of protocol extensibility
- A back-pressure method for query results
- All the "usual suspects": support for binary format, partial query results,
large objects and so on

### Vladimir Sitnikov

- compressed streams over network
- "query response status" messages being sent in plain text (`insert 1`, `select 10`, etc.).
Having some binary there would make things easier to parse.
- unable to use prepared statements for `set "application_name"=...`, etc
