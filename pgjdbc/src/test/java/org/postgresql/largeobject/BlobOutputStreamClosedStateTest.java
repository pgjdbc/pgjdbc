/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Fails when a closed stream reaches for the buffer it released.
 *
 * <p>{@code close()} drops the buffer, which is up to the stream's maximum and is never read
 * again. Every operation past that point has to be refused before anything touches it, so a
 * released buffer surfaces as the closed-stream error and not as a {@link NullPointerException}.
 * </p>
 */
class BlobOutputStreamClosedStateTest {
  private Connection con;
  private LargeObjectManager lom;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
    lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  @Test
  void aClosedStreamRefusesEveryOperation() throws Exception {
    long oid = lom.createLO();
    byte[] payload = {1, 2, 3, 4, 5};
    try (LargeObject lo = lom.open(oid)) {
      BlobOutputStream os = new BlobOutputStream(lo, 1024);
      os.write(payload, 0, payload.length);
      os.close();

      assertThrows(IOException.class, () -> os.write(42));
      assertThrows(IOException.class, () -> os.write(payload, 0, payload.length));
      assertThrows(IOException.class, os::flush);
      os.close();
    }

    try (LargeObject lo = lom.open(oid)) {
      assertArrayEquals(payload, lo.read(lo.size()), "close() must still have flushed the buffer");
    }
    lom.delete(oid);
  }
}
