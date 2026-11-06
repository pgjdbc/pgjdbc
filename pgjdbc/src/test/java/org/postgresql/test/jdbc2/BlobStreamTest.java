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

import java.io.InputStream;
import java.sql.Blob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

class BlobStreamTest extends BaseTest4 {
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
  void openingStreamUsesOneRoundtrip() throws Exception {
    Blob blob = createBlob(CONTENTS);
    try {
      // keep this call: it opens the parent descriptor before counting.
      // without it, getBinaryStream() performs two lo_open calls instead of one.
      assertArrayEquals(new byte[]{30}, blob.getBytes(3, 1));
      long before = counters.roundtrips.get();
      try (InputStream stream = blob.getBinaryStream()) {
        assertEquals(1, counters.roundtrips.get() - before,
            "Opening a full Blob stream should only open its independent handle");
      }
    } finally {
      blob.free();
    }
  }

  private Blob createBlob(byte[] contents) throws SQLException {
    LargeObjectManager manager = con.unwrap(PGConnection.class).getLargeObjectAPI();
    long oid = manager.createLO();
    try (LargeObject object = manager.open(oid)) {
      object.write(contents);
    }
    try (PreparedStatement statement = con.prepareStatement("SELECT ?::oid")) {
      statement.setLong(1, oid);
      try (ResultSet result = statement.executeQuery()) {
        assertTrue(result.next());
        return result.getBlob(1);
      }
    }
  }
}
