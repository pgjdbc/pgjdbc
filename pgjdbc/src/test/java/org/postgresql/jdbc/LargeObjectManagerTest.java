/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.StrangeInputStream;
import org.postgresql.test.util.StrangeOutputStream;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

class LargeObjectManagerTest {

  /*
   * It is possible for PostgreSQL to send a ParameterStatus message after an ErrorResponse
   * Receiving such a message should not lead to an invalid connection state
   * See https://github.com/pgjdbc/pgjdbc/issues/2237
   */
  @Test
  void openWithErrorAndSubsequentParameterStatusMessageShouldLeaveConnectionInUsableStateAndUpdateParameterStatus() throws Exception {
    try (PgConnection con = (PgConnection) TestUtil.openDB()) {
      Assumptions.assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0));
      con.setAutoCommit(false);
      String originalApplicationName = con.getParameterStatus("application_name");
      try (Statement statement = con.createStatement()) {
        statement.execute("begin;");
        // Set transaction application_name to trigger ParameterStatus message after error
        // https://www.postgresql.org/docs/14/protocol-flow.html#PROTOCOL-ASYNC
        String updatedApplicationName = "LargeObjectManagerTest-application-name";
        statement.execute("set application_name to '" + updatedApplicationName + "'");

        LargeObjectManager loManager = con.getLargeObjectAPI();
        try {
          loManager.open(0, false);
          fail("Succeeded in opening a nonexistent large object");
        } catch (PSQLException e) {
          assertEquals(PSQLState.UNDEFINED_OBJECT.getState(), e.getSQLState());
        }

        // Should be reset to original application name
        assertEquals(originalApplicationName, con.getParameterStatus("application_name"));
      }
    }
  }


  /**
   * Writes data into a large object and reads it back.
   * The verifications are:
   *  1) input size should match the output size
   *  2) input checksum should match the output checksum
   */
  @Test
  void objectWriteThenRead() throws Throwable {
    try (PgConnection con = (PgConnection) TestUtil.openDB()) {
      // LO is not supported in auto-commit mode
      con.setAutoCommit(false);
      LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
      for (int i = 0; i < 100000 && System.currentTimeMillis() < deadline; i++) {
        long seed = ThreadLocalRandom.current().nextLong();
        objectWriteThenRead(lom, seed, md);
        // Creating too many large objects in a single transaction might lead to "ERROR: out of shared memory"
        if (i % 1000 == 0) {
          con.commit();
        }
      }
    }
  }

  private final byte[][] buffers = new byte[][]{new byte[1024], new byte[8192], new byte[128 * 1024]};

  private void objectWriteThenRead(LargeObjectManager lom, long seed, MessageDigest md) throws SQLException, IOException {
    long loId = lom.createLO();
    try (LargeObject lo = lom.open(loId)) {
      Random rnd = new Random(seed);
      int expectedLength = rnd.nextInt(1000000);
      // Write data to the stream
      // We do not use try-with-resources as closing the output stream would close the large object
      OutputStream os = lo.getOutputStream();
      {
        byte[] buf = new byte[Math.min(256 * 1024, expectedLength)];
        // Do not use try-with-resources to avoid closing the large object
        StrangeOutputStream fs = new StrangeOutputStream(os, rnd.nextLong(), 0.1);
        {
          int len = expectedLength;
          while (len > 0) {
            int writeSize = Math.min(buf.length, len);
            rnd.nextBytes(buf);
            md.update(buf, 0, writeSize);
            fs.write(buf, 0, writeSize);
            len -= writeSize;
          }
          fs.flush();
        }
      }
      // Verify the size of the resulting blob
      assertEquals(expectedLength, lo.tell(), "Lob position after writing the data");

      // Rewing the position to the beginning
      // Ideally, .getInputStream should start reading from the beginning, however, it is not the
      // case yet
      lo.seek(0);

      // Read out the data and verify its contents
      byte[] expectedChecksum = md.digest();
      md.reset();
      int actualLength = 0;
      // Do not use try-with-resources to avoid closing the large object
      InputStream is = lo.getInputStream();
      {
        try (StrangeInputStream fs = new StrangeInputStream(is, rnd.nextLong())) {
          while (true) {
            int bufferIndex = rnd.nextInt(buffers.length);
            byte[] buf = buffers[bufferIndex];
            int read = fs.read(buf);
            if (read == -1) {
              break;
            }
            actualLength += read;
            md.update(buf, 0, read);
          }
        }
        byte[] actualChecksum = md.digest();
        if (!Arrays.equals(expectedChecksum, actualChecksum)) {
          fail("Checksum of the input and output streams mismatch."
              + " Input actualLength: " + expectedLength
              + ", output actualLength: " + actualLength
              + ", test seed: " + seed
              + ", large object id: " + loId
          );
        }
      }
    } catch (Throwable t) {
      String message = "Test seed is " + seed;
      t.addSuppressed(new Throwable(message) {
        @Override
        public Throwable fillInStackTrace() {
          return this;
        }
      });
      throw t;
    } finally {
      lom.delete(loId);
    }
  }
}
