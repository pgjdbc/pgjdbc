/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.largeobject;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ReadBlob {
  @Param({"16384", "1048576"})
  public int bytes;

  private Connection connection;
  private PreparedStatement select;
  private final byte[] buffer = new byte[8192];
  private final CRC32 checksum = new CRC32();
  private long expectedChecksum;

  @Setup
  public void setUp() throws SQLException {
    connection = TestUtil.openDB();
    connection.setAutoCommit(false);
    LargeObjectManager manager = connection.unwrap(PGConnection.class).getLargeObjectAPI();
    long oid = manager.createLO();
    byte[] contents = new byte[bytes];
    new Random(1).nextBytes(contents);
    checksum.update(contents);
    expectedChecksum = checksum.getValue();
    try (LargeObject object = manager.open(oid)) {
      object.write(contents);
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TEMPORARY TABLE pgjdbc_read_blob (id int PRIMARY KEY, value oid)");
    }
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO pgjdbc_read_blob VALUES (1, ?::oid)")) {
      insert.setLong(1, oid);
      insert.executeUpdate();
    }
    select = connection.prepareStatement("SELECT value FROM pgjdbc_read_blob WHERE id = 1");
  }

  @Benchmark
  public long selectAndRead() throws SQLException, IOException {
    checksum.reset();
    long count = 0;
    try (ResultSet result = select.executeQuery()) {
      if (!result.next()) {
        throw new IllegalStateException("The Blob fixture row is missing");
      }
      Blob blob = result.getBlob(1);
      try (InputStream stream = blob.getBinaryStream()) {
        int read;
        while ((read = stream.read(buffer)) != -1) {
          checksum.update(buffer, 0, read);
          count += read;
        }
      } finally {
        blob.free();
      }
    }
    long actualChecksum = checksum.getValue();
    if (count != bytes || actualChecksum != expectedChecksum) {
      throw new IllegalStateException("Blob content differs from the fixture");
    }
    return actualChecksum;
  }

  @TearDown
  public void tearDown() throws SQLException {
    try {
      if (select != null) {
        select.close();
      }
    } finally {
      if (connection != null) {
        try {
          connection.rollback();
        } finally {
          connection.close();
        }
      }
    }
  }
}
