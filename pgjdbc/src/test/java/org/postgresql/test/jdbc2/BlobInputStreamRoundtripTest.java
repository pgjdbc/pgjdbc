/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * A stream over a large object descriptor whose position the driver already knows starts reading
 * without asking the server for the position with {@code lo_tell}.
 *
 * <p>Reading the five-byte object to the end takes two {@code loread} calls: one returns the
 * bytes, the next returns none. A stream with a limit stops at the limit and skips the second call.
 * A stream that asks for its starting position adds one more round trip to either count.</p>
 */
class BlobInputStreamRoundtripTest extends BaseTest4 {
  private static final byte[] CONTENTS = {10, 20, 30, 40, 50};
  private final CountingSocketFactory.Counters counters = CountingSocketFactory.register();

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.AUTOSAVE.set(props, "never");
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, counters.key());
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    con.setAutoCommit(false);
  }

  @Override
  protected void tearDown() throws SQLException {
    try {
      if (con != null) {
        con.rollback();
      }
    } finally {
      try {
        super.tearDown();
      } finally {
        CountingSocketFactory.unregister(counters);
      }
    }
  }

  @Test
  void fullBlobStreamReadsWithoutTell() throws Exception {
    Blob blob = selectBlob(createLargeObject());
    try (InputStream stream = blob.getBinaryStream()) {
      assertReadsToEnd(CONTENTS, 2, stream);
    } finally {
      blob.free();
    }
  }

  @Test
  void blobStreamFromFirstByteOpensWithoutSeek() throws Exception {
    Blob blob = selectBlob(createLargeObject());
    try {
      // getBytes opens the Blob's own descriptor, so only the stream's descriptor is counted
      assertArrayEquals(new byte[]{30}, blob.getBytes(3, 1));
      long before = counters.roundtrips.get();
      try (InputStream stream = blob.getBinaryStream(1, CONTENTS.length)) {
        assertEquals(1, counters.roundtrips.get() - before,
            "round trips of getBinaryStream(1, " + CONTENTS.length + "), expected lo_open only");
        assertReadsToEnd(CONTENTS, 1, stream);
      }
    } finally {
      blob.free();
    }
  }

  @Test
  void blobStreamFromThirdByteReadsWithoutTell() throws Exception {
    Blob blob = selectBlob(createLargeObject());
    try (InputStream stream = blob.getBinaryStream(3, 2)) {
      assertReadsToEnd(new byte[]{30, 40}, 1, stream);
    } finally {
      blob.free();
    }
  }

  @Test
  void freshlyOpenedLargeObjectReadsWithoutTell() throws Exception {
    long oid = createLargeObject();
    try (LargeObject lo = largeObjectApi().open(oid, LargeObjectManager.READ);
         InputStream stream = lo.getInputStream()) {
      assertReadsToEnd(CONTENTS, 2, stream);
    }
  }

  @Test
  void largeObjectReadBeforeStreamCostsATell() throws Exception {
    long oid = createLargeObject();
    try (LargeObject lo = largeObjectApi().open(oid, LargeObjectManager.READ)) {
      assertArrayEquals(new byte[]{10, 20}, lo.read(2));
      try (InputStream stream = lo.getInputStream()) {
        long before = counters.roundtrips.get();
        assertArrayEquals(new byte[]{30, 40, 50}, readToEnd(stream));
        assertEquals(3, counters.roundtrips.get() - before,
            "round trips to read the rest after LargeObject.read, expected lo_tell and two loread");
      }
    }
  }

  private void assertReadsToEnd(byte[] expected, int loreadCalls, InputStream stream)
      throws IOException {
    long before = counters.roundtrips.get();
    assertArrayEquals(expected, readToEnd(stream));
    assertEquals(loreadCalls, counters.roundtrips.get() - before,
        "round trips to read the stream to the end, expected loread only, with no lo_tell");
  }

  private static byte[] readToEnd(InputStream stream) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = stream.read(buffer)) != -1) {
      out.write(buffer, 0, read);
    }
    return out.toByteArray();
  }

  private LargeObjectManager largeObjectApi() throws SQLException {
    return con.unwrap(PGConnection.class).getLargeObjectAPI();
  }

  private long createLargeObject() throws SQLException {
    LargeObjectManager manager = largeObjectApi();
    long oid = manager.createLO();
    try (LargeObject lo = manager.open(oid)) {
      lo.write(CONTENTS);
    }
    return oid;
  }

  private Blob selectBlob(long oid) throws SQLException {
    try (PreparedStatement statement = con.prepareStatement("SELECT ?::oid")) {
      statement.setLong(1, oid);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getBlob(1);
      }
    }
  }
}
