---
title: "Physical replication and logical decoding"
description: "JDBC access to PostgreSQL physical replication and logical decoding: opening a replication-mode connection (`replication=database` / `true`), the `PGReplicationConnection` API, and consuming WAL records or row-level change events."
date: 2026-05-13T00:00:00Z
draft: false
weight: 20
toc: true
last_reviewed: "2026-05-22"
aliases:
    - "/documentation/server-prepare/#physical-and-logical-replication-api/"
    - "/documentation/server-prepare/#configure-database/"
    - "/documentation/server-prepare/#logical-replication/"
    - "/documentation/server-prepare/#physical-replication/"
---

## Physical replication and logical decoding API

{{< review date="2026-05-22" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PGConnection.java | pgjdbc/src/main/java/org/postgresql/PGConnection.java | 268-271
- PGReplicationConnection.java | pgjdbc/src/main/java/org/postgresql/replication/PGReplicationConnection.java | 14-44
- PGProperty.java | pgjdbc/src/main/java/org/postgresql/PGProperty.java | 780-805
- BaseDataSource.java | pgjdbc/src/main/java/org/postgresql/ds/common/BaseDataSource.java | 1323-1352
{{< /review >}}

PostgreSQL® 9.4 (released in December 2014) introduced a new feature called logical decoding. Logical decoding allows
changes from a database to be streamed in real-time to an external system. The difference between physical replication and
logical decoding is that logical decoding sends data over in a logical format whereas physical replication sends WAL data
over in a binary format. Logical decoding output is scoped to a database, and table-level filtering depends on the output
plugin or on higher-level PostgreSQL® logical replication features. Binary replication replicates the entire cluster in an
all-or-nothing fashion; there is no way to get a specific table or database using binary replication.

Prior to logical decoding, keeping an external system synchronised in real time was problematic. The application would
have to update or invalidate the appropriate cache entries, reindex the data in your search engine, send it to your analytics
system, and so on.

This suffers from race conditions and reliability problems. For example, if slightly different data gets written to two
different datastores (perhaps due to a bug or a race condition), the contents of the datastores will gradually drift
apart and become more and more inconsistent over time. Recovering from such gradual data corruption is difficult.

Logical decoding takes the database's write-ahead log (WAL) and gives us access to row-level change events:
every time a row in a table is inserted, updated, or deleted, that's an event. Those events are grouped by transaction,
and appear in the order in which they were committed to the database. Aborted or rolled-back transactions
do not appear in the stream. Thus, if you apply the change events in the same order, you end up with an exact,
transactionally consistent copy of the database. It looks like the Event Sourcing pattern that you previously implemented
in your application, but now it's available out of the box from the PostgreSQL® database.

For access to real-time changes, PostgreSQL® provides the streaming replication protocol. The replication protocol can be
physical or logical. The physical replication protocol is used for primary/standby replication. The logical replication
protocol can be used to stream changes to an external system.

Since the JDBC API does not include replication, `PGConnection` implements the PostgreSQL® API.

## Configure database

Your database should be configured to enable logical or physical replication.

### postgresql.conf

* Property `max_wal_senders` should be at least equal to the number of replication consumers.
* Property `wal_keep_size` should specify the minimum amount of past WAL files kept in `pg_wal`.
* Property `wal_level` for logical decoding should be equal to `logical`.
* Property `max_replication_slots` should be greater than zero for logical decoding, because logical decoding can't
 work without a replication slot.

### pg_hba.conf

Allow a user with replication privileges to connect to the replication stream.

```sql
local   replication   all                   trust
host    replication   all   127.0.0.1/32    md5
host    replication   all   ::1/128         md5
```

### Configuration for examples

*postgresql.conf*

```ini
max_wal_senders = 4             # max number of walsender processes
wal_keep_size = 64MB            # minimum size of past WAL files kept in pg_wal; 0 disables
wal_level = logical             # minimal, replica, or logical
max_replication_slots = 4       # max number of replication slots
```

*pg_hba.conf*

```sql
# Allow replication connections from localhost, by a user with the
# replication privilege.
local   replication   all                   trust
host    replication   all   127.0.0.1/32    md5
host    replication   all   ::1/128         md5
```

## Logical decoding

{{< review date="2026-05-22" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- LogicalCreateSlotBuilder.java | pgjdbc/src/main/java/org/postgresql/replication/fluent/logical/LogicalCreateSlotBuilder.java | 40-88
- ChainedLogicalCreateSlotBuilder.java | pgjdbc/src/main/java/org/postgresql/replication/fluent/logical/ChainedLogicalCreateSlotBuilder.java | 16-26
- LogicalStreamBuilder.java | pgjdbc/src/main/java/org/postgresql/replication/fluent/logical/LogicalStreamBuilder.java | 40-94
- V3ReplicationProtocol.java | pgjdbc/src/main/java/org/postgresql/core/v3/replication/V3ReplicationProtocol.java | 40-118
- V3PGReplicationStream.java | pgjdbc/src/main/java/org/postgresql/core/v3/replication/V3PGReplicationStream.java | 72-123
- LogicalReplicationTest.java | pgjdbc/src/test/java/org/postgresql/replication/LogicalReplicationTest.java | 44-68
{{< /review >}}

Logical decoding uses a replication slot to reserve WAL logs on the server, and it also defines which decoding plugin to
use to decode the WAL logs to the required format. For example, you can decode changes as JSON, protobuf, etc. To demonstrate
how to use the pgJDBC replication API we will use the `test_decoding` plugin that is included in the `postgresql-contrib`
package, but you can use your own decoding plugin. There are a few on GitHub that can be used as examples.

In order to use the replication API, the connection has to be created in replication mode. In this mode the connection
is not available to execute normal SQL commands, and can only be used with replication-protocol commands. This is a restriction imposed by PostgreSQL®.

The [`replication`](/documentation/reference/connection-properties/#prop-replication) property should be paired with [`assumeMinServerVersion`](/documentation/reference/connection-properties/#prop-assumeminserverversion) set to `9.4` or higher (the backend version that introduced logical decoding). Without it the driver issues extra version-probing round-trips before entering walsender mode, which can fail on connections that only accept replication commands.

##### Example 9.4. Create replication connection.

```java
String url = "jdbc:postgresql://localhost:5432/postgres";
Properties props = new Properties();
PGProperty.USER.set(props, "postgres");
PGProperty.PASSWORD.set(props, "postgres");
PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
PGProperty.REPLICATION.set(props, "database");
PGProperty.PREFER_QUERY_MODE.set(props, "simple");

Connection con = DriverManager.getConnection(url, props);
PGConnection replConnection = con.unwrap(PGConnection.class);
```

The entire replication API is grouped in `org.postgresql.replication.PGReplicationConnection` and is available via
`org.postgresql.PGConnection#getReplicationAPI`.

Before you can start the replication protocol, you need to have a replication slot, which can also be created via the pgJDBC API.

##### Example 9.5. Create replication slot via pgJDBC API

```java
replConnection.getReplicationAPI()
    .createReplicationSlot()
    .logical()
    .withSlotName("demo_logical_slot")
    .withOutputPlugin("test_decoding")
    .make();
```

Once we have the replication slot, we can create a ReplicationStream.

##### Example 9.6. Create logical decoding stream.

```java
PGReplicationStream stream =
    replConnection.getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName("demo_logical_slot")
        .withSlotOption("include-xids", false)
        .withSlotOption("skip-empty-xacts", true)
        .start();
```

The replication stream will send all changes since the creation of the replication slot, or from the replication slot's
restart LSN if the slot was already used for replication. You can also start streaming changes from a particular LSN position;
in that case the LSN position should be specified when you create the replication stream.

##### Example 9.7. Create logical decoding stream from particular position.

```java
LogSequenceNumber waitLSN = LogSequenceNumber.valueOf("6F/E3C53568");

PGReplicationStream stream =
    replConnection.getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName("demo_logical_slot")
        .withSlotOption("include-xids", false)
        .withSlotOption("skip-empty-xacts", true)
        .withStartPosition(waitLSN)
        .start();
```

Via `withSlotOption` we can also specify options that will be sent to our output plugin; this allows the user to customise decoding.
For example, I have my own output plugin that has a property `sensitive=true` that will include changes from sensitive columns in
the change event.

##### Example 9.8. Example output with include-xids=true

```sql
BEGIN 105779
table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'previous value'
COMMIT 105779
```

##### Example 9.9. Example output with include-xids=false

```sql
BEGIN
table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'previous value'
COMMIT
```

During replication the database and consumer periodically exchange ping messages. When the database or client does not receive
a ping message within the configured timeout, replication is deemed to have stopped, an exception will be thrown, and
the database will free resources. In PostgreSQL® the ping timeout is configured by the property `wal_sender_timeout`
(default = 60 seconds). The replication stream in pgJDBC can be configured to send feedback (ping) when required or at a time interval.
It is recommended to send feedback (ping) to the database more often than the configured `wal_sender_timeout`. In production systems
I use a value equal to `wal_sender_timeout / 3`. This avoids potential problems with networks and allows changes to be
streamed without disconnects by timeout. To specify the feedback interval, use the `withStatusInterval` method.

##### Example 9.10. Replication stream with configured feedback interval equal to 20 sec

```java
PGReplicationStream stream =
    replConnection.getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName("demo_logical_slot")
        .withSlotOption("include-xids", false)
        .withSlotOption("skip-empty-xacts", true)
        .withStatusInterval(20, TimeUnit.SECONDS)
        .start();
```

After creating `PGReplicationStream`, it's time to start receiving changes in real time.

Changes can be received from the stream as blocking (`org.postgresql.replication.PGReplicationStream#read`) or as
non-blocking (`org.postgresql.replication.PGReplicationStream#readPending`).
Both methods receive changes as a `java.nio.ByteBuffer` with the payload from the output plugin. We can't receive
part of a message, only the full message that was sent by the output plugin. The ByteBuffer contains the message in a format
defined by the decoding output plugin; it can be a simple String, JSON, or whatever the plugin determines. That's why
pgJDBC returns the raw ByteBuffer instead of making assumptions.

##### Example 9.11. Example send message from output plugin.

```java
OutputPluginPrepareWrite(ctx, true);
appendStringInfo(ctx->out, "BEGIN %u", txn->xid);
OutputPluginWrite(ctx, true);
```

**Example 9.12. Receive changes via replication stream.**

```java
while (true) {
    //non blocking receive message
    ByteBuffer msg = stream.readPending();

    if (msg == null) {
        TimeUnit.MILLISECONDS.sleep(10 L);
        continue;
    }

    int offset = msg.arrayOffset();
    byte[] source = msg.array();
    int length = source.length - offset;
    System.out.println(new String(source, offset, length));
}
```

As mentioned previously, the replication stream should periodically send feedback to the database to prevent disconnect by
timeout. Feedback is automatically sent when `read` or `readPending` are called if it's time to send feedback. Feedback
can also be sent via `org.postgresql.replication.PGReplicationStream#forceUpdateStatus()` regardless of the timeout. Another
important duty of feedback is to provide the server with the Logical Sequence Number (LSN) that has been successfully received
and applied to the consumer; this is necessary for monitoring and to truncate or archive WALs that are no longer needed. In the
event that replication is restarted, it will start from the last successfully processed LSN that was sent via feedback to the database.

The API provides the following feedback mechanism to indicate the LSN successfully applied by the current consumer. LSNs
before this can be truncated or archived: `org.postgresql.replication.PGReplicationStream#setFlushedLSN` and
`org.postgresql.replication.PGReplicationStream#setAppliedLSN`. You can always get the last received LSN via
`org.postgresql.replication.PGReplicationStream#getLastReceiveLSN`.

##### Example 9.13. Add feedback indicating a successfully process LSN

```java
while (true) {
    //Receive last successfully send to queue message. LSN ordered.
    LogSequenceNumber successfullySendToQueue = getQueueFeedback();
    if (successfullySendToQueue != null) {
        stream.setAppliedLSN(successfullySendToQueue);
        stream.setFlushedLSN(successfullySendToQueue);
    }

    //non blocking receive message
    ByteBuffer msg = stream.readPending();

    if (msg == null) {
        TimeUnit.MILLISECONDS.sleep(10 L);
        continue;
    }

    asyncSendToQueue(msg, stream.getLastReceiveLSN());
}
```

##### Example 9.14. Full example of logical decoding

```java
String url = "jdbc:postgresql://localhost:5432/test";
Properties props = new Properties();
PGProperty.USER.set(props, "postgres");
PGProperty.PASSWORD.set(props, "postgres");
PGProperty.ASSUME_MIN_SERVER_VERSION.set(props, "9.4");
PGProperty.REPLICATION.set(props, "database");
PGProperty.PREFER_QUERY_MODE.set(props, "simple");

Connection con = DriverManager.getConnection(url, props);
PGConnection replConnection = con.unwrap(PGConnection.class);

replConnection.getReplicationAPI()
    .createReplicationSlot()
    .logical()
    .withSlotName("demo_logical_slot")
    .withOutputPlugin("test_decoding")
    .make();

//some changes after create replication slot to demonstrate receive it
sqlConnection.setAutoCommit(true);
Statement st = sqlConnection.createStatement();
st.execute("insert into test_logic_table(name) values('first tx changes')");
st.close();

st = sqlConnection.createStatement();
st.execute("update test_logic_table set name = 'second tx change' where pk = 1");
st.close();

st = sqlConnection.createStatement();
st.execute("delete from test_logic_table where pk = 1");
st.close();

PGReplicationStream stream =
    replConnection.getReplicationAPI()
    .replicationStream()
    .logical()
    .withSlotName("demo_logical_slot")
    .withSlotOption("include-xids", false)
    .withSlotOption("skip-empty-xacts", true)
    .withStatusInterval(20, TimeUnit.SECONDS)
    .start();

while (true) {
    //non blocking receive message
    ByteBuffer msg = stream.readPending();

    if (msg == null) {
        TimeUnit.MILLISECONDS.sleep(10 L);
        continue;
    }

    int offset = msg.arrayOffset();
    byte[] source = msg.array();
    int length = source.length - offset;
    System.out.println(new String(source, offset, length));

    //feedback
    stream.setAppliedLSN(stream.getLastReceiveLSN());
    stream.setFlushedLSN(stream.getLastReceiveLSN());
}
```

Where output looks like this, where each line is a separate message.

```sql
BEGIN
table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'first tx changes'
COMMIT
BEGIN
table public.test_logic_table: UPDATE: pk[integer]:1 name[character varying]:'second tx change'
COMMIT
BEGIN
table public.test_logic_table: DELETE: pk[integer]:1
COMMIT
```

## Physical replication

{{< review date="2026-05-22" rev="bd1af18230371879fb4127ae28800cf9a8a8c77d" >}}
- PhysicalStreamBuilder.java | pgjdbc/src/main/java/org/postgresql/replication/fluent/physical/PhysicalStreamBuilder.java | 34-57
- V3ReplicationProtocol.java | pgjdbc/src/main/java/org/postgresql/core/v3/replication/V3ReplicationProtocol.java | 48-87
- PhysicalReplicationTest.java | pgjdbc/src/test/java/org/postgresql/replication/PhysicalReplicationTest.java | 58-80
- PhysicalReplicationTest.java | pgjdbc/src/test/java/org/postgresql/replication/PhysicalReplicationTest.java | 83-104
{{< /review >}}

The API for physical replication looks like the API for logical decoding. Physical replication does not require a replication
slot, and the ByteBuffer will contain the binary form of WAL logs. The binary WAL format is a very low-level API, and can change
from version to version. That is why replication between different major PostgreSQL® versions is not possible. However, physical
replication can carry important data that is not available via logical decoding. That is why pgJDBC contains an
implementation for both.

**Example 9.15. Use physical replication**

```java
LogSequenceNumber lsn = getCurrentLSN();

Statement st = sqlConnection.createStatement();
st.execute("insert into test_physic_table(name) values('previous value')");
st.close();

PGReplicationStream stream =
    pgConnection
    .getReplicationAPI()
    .replicationStream()
    .physical()
    .withStartPosition(lsn)
    .start();

ByteBuffer read = stream.read();
```
