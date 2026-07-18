/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.jdbc.CodecDepth;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CodecDepth}.
 */
public class CodecDepthTest {

  @BeforeEach
  void setUp() {
    CodecDepth.clear();
  }

  @AfterEach
  void tearDown() {
    CodecDepth.clear();
  }

  @Test
  void testEnterAndExit() throws Exception {
    assertEquals(0, CodecDepth.current());

    CodecDepth.enter();
    assertEquals(1, CodecDepth.current());

    CodecDepth.enter();
    assertEquals(2, CodecDepth.current());

    CodecDepth.exit();
    assertEquals(1, CodecDepth.current());

    CodecDepth.exit();
    assertEquals(0, CodecDepth.current());
  }

  @Test
  void testMaxDepthExceeded() throws Exception {
    // Enter up to max depth (64)
    for (int i = 0; i < 64; i++) {
      CodecDepth.enter();
    }
    assertEquals(64, CodecDepth.current());

    // Next enter should throw
    assertThrows(PSQLException.class, CodecDepth::enter);
  }

  @Test
  void testClear() throws Exception {
    CodecDepth.enter();
    CodecDepth.enter();
    assertEquals(2, CodecDepth.current());

    CodecDepth.clear();
    assertEquals(0, CodecDepth.current());
  }

  @Test
  void testExitBelowZero() throws Exception {
    // Exit without enter should stay at 0 (guarded)
    CodecDepth.exit();
    assertEquals(0, CodecDepth.current());
  }
}
