/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import org.postgresql.core.BaseConnection;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.fastpath.FastpathArg;
import org.postgresql.util.ByteStreamWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Large object held in memory that records the length of every {@code lowrite} it receives, so a
 * test can see how {@link BlobOutputStream} splits a write without a server.
 *
 * <p>{@link BlobOutputStream} writes sequentially from position zero, so the payloads concatenated
 * in the order they arrive are the contents of the large object.</p>
 */
class RecordingLargeObject extends LargeObject {
  private final List<Integer> lowriteLengths = new ArrayList<>();
  private final ByteArrayOutputStream contents = new ByteArrayOutputStream();

  RecordingLargeObject() throws SQLException {
    super(new NoServerFastpath(), 0, LargeObjectManager.READWRITE);
  }

  /**
   * Runs at the start of every {@code lowrite}, before the payload is recorded.
   */
  protected void beforeLowrite() throws SQLException {
  }

  int[] lowriteLengths() {
    int[] lengths = new int[lowriteLengths.size()];
    for (int i = 0; i < lengths.length; i++) {
      lengths[i] = lowriteLengths.get(i);
    }
    return lengths;
  }

  byte[] contents() {
    return contents.toByteArray();
  }

  @Override
  public void write(byte[] buf) throws SQLException {
    write(buf, 0, buf.length);
  }

  @Override
  public void write(byte[] buf, int off, int len) throws SQLException {
    beforeLowrite();
    lowriteLengths.add(len);
    contents.write(buf, off, len);
  }

  @Override
  public void write(ByteStreamWriter writer) throws SQLException {
    beforeLowrite();
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    try {
      writer.writeTo(() -> payload);
    } catch (IOException e) {
      throw new SQLException(e);
    }
    lowriteLengths.add(payload.size());
    contents.write(payload.toByteArray(), 0, payload.size());
  }

  /**
   * Returns four zero bytes for every call. {@link LargeObject} reads them as descriptor 0 from
   * {@code lo_open} and ignores the result of {@code lo_close}; the writes never reach it.
   */
  private static class NoServerFastpath extends Fastpath {
    NoServerFastpath() {
      super((BaseConnection) Proxy.newProxyInstance(
          RecordingLargeObject.class.getClassLoader(),
          new Class<?>[]{BaseConnection.class},
          (proxy, method, args) -> null));
    }

    @Override
    public byte[] fastpath(String name, FastpathArg[] args) {
      return new byte[4];
    }
  }
}
