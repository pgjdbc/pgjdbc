/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGStatement;
import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.test.TestUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Verifies that a codec registered on a live connection's {@link CodecRegistry} is picked up by the
 * next execution of a prepared statement, and that a result set already open keeps the codec it
 * resolved. The codec cache lives on {@link org.postgresql.jdbc.PgResultSet}, not on the shared
 * {@link org.postgresql.core.Field} held in the prepared-statement cache, so the two guarantees hold
 * even when the statement is server-prepared and its {@code Field[]} is reused across executions.
 */
class CodecRegistrationVisibilityTest {

  private static final String MARKER = "codec-registration-marker";

  /** A codec that decodes every value of its type to {@link #MARKER}, so its use is observable. */
  private static final class MarkerCodec implements BinaryCodec, TextCodec {
    @Override
    public String getTypeName() {
      return "int4";
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return String.class;
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
        CodecContext ctx) {
      return MARKER;
    }

    @Override
    public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) {
      return MARKER;
    }

    @Override
    public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) {
      return new byte[0];
    }

    @Override
    public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) {
      return "";
    }
  }

  private Connection con;
  private CodecRegistry registry;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    registry = con.unwrap(PgConnection.class).getTypeInfo().getCodecRegistry();
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.closeDB(con);
  }

  @Test
  void registrationTakesEffectOnNextExecution() throws Exception {
    // prepareThreshold=1 makes the statement server-prepared from the first execution, so the
    // Field[] is cached on the SimpleQuery and reused across executions -- the exact condition
    // under which a per-Field codec cache would have gone stale after registration.
    try (PreparedStatement ps = con.prepareStatement("SELECT 42")) {
      ps.unwrap(PGStatement.class).setPrepareThreshold(1);

      Object before;
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        before = rs.getObject(1);
      }
      assertTrue(ps.unwrap(PGStatement.class).isUseServerPrepare(),
          "prepareThreshold=1 should make the statement server-prepared so its Field[] is reused");
      assertTrue(before instanceof Integer, "default int4 codec decodes to Integer, was: " + before);

      registry.registerByOid(Oid.INT4, new MarkerCodec());

      Object after;
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        after = rs.getObject(1);
      }
      assertEquals(MARKER, after,
          "the re-execution must resolve the newly registered codec, not the one the first "
              + "execution had cached");
    }
  }

  @Test
  void openResultSetKeepsResolvedCodec() throws Exception {
    try (PreparedStatement ps = con.prepareStatement("SELECT i FROM generate_series(1, 3) AS i");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      // First read resolves and caches the default int4 codec for this result set.
      Object firstRow = rs.getObject(1);
      assertTrue(firstRow instanceof Integer, "default int4 codec decodes to Integer, was: " + firstRow);

      registry.registerByOid(Oid.INT4, new MarkerCodec());

      assertTrue(rs.next());
      // The open result set keeps the codec it resolved before the registration.
      Object secondRow = rs.getObject(1);
      assertTrue(secondRow instanceof Integer,
          "an already-open result set must keep the codec it resolved, was: " + secondRow);
    }
  }
}
