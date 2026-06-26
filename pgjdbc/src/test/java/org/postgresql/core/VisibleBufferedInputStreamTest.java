/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

class VisibleBufferedInputStreamTest {

  /**
   * Wraps a byte array but only returns up to {@code chunk} bytes per {@code read()} call,
   * forcing {@link VisibleBufferedInputStream#scanCStringLength(int, String, int)} to invoke
   * {@code readMore()} mid-scan even when the underlying data already contains the NUL.
   */
  private static InputStream chunkedStream(byte[] data, int chunk) {
    return new ByteArrayInputStream(data) {
      @Override
      public synchronized int read(byte[] b, int off, int len) {
        return super.read(b, off, Math.min(len, chunk));
      }
    };
  }

  @Test
  void scanCStringLengthBoundedAcrossPartialReads() throws IOException {
    // application_name\0Driver Tests\0: 30 bytes total, the first ParameterStatus body
    // size that triggered the original double-counting bug under partial-read conditions.
    byte[] body = ("application_name\0Driver Tests\0").getBytes(StandardCharsets.US_ASCII);
    assertEquals(30, body.length);

    // Force the underlying read to deliver only 5 bytes at a time so the scan must call
    // readMore() multiple times mid-scan, exercising the resume-from-scanned path.
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(chunkedStream(body, 5), 8);

    int nameLen = in.scanCStringLength(30, "ParameterStatus", 34);
    assertEquals(17, nameLen, "application_name + NUL");
    in.skip(nameLen);

    int valueLen = in.scanCStringLength(30, "ParameterStatus", 34);
    assertEquals(13, valueLen, "Driver Tests + NUL");
    in.skip(valueLen);
  }

  @Test
  void scanCStringLengthRejectsOverlongString() throws IOException {
    // 31-char name + NUL = 32 bytes; budget 30 must reject.
    byte[] data = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\0".getBytes(StandardCharsets.US_ASCII);
    assertEquals(32, data.length);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ByteArrayInputStream(data), 64);
    IOException e = assertThrows(IOException.class,
        () -> in.scanCStringLength(30, "ParameterStatus", 34));
    // The error must surface both the packet name and the declared message length so an
    // operator triaging a desync can correlate it with a wire capture.
    String msg = e.getMessage();
    assertTrue(msg.contains("ParameterStatus"), msg);
    assertTrue(msg.contains("34"), msg);
    assertTrue(msg.contains("30"), msg);
  }

  @Test
  void scanCStringLengthAcceptsExactBudget() throws IOException {
    // 29-char name + NUL = 30 bytes, budget 30 must accept.
    byte[] data = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaa\0".getBytes(StandardCharsets.US_ASCII);
    assertEquals(30, data.length);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ByteArrayInputStream(data), 64);
    assertEquals(30, in.scanCStringLength(30, "ParameterStatus", 34));
  }

  @Test
  void scanCStringLengthBudgetCheckSurvivesCompaction() throws IOException {
    // 40-char name + NUL = 41 bytes, budget 30: must reject even when the bytes are
    // delivered in tiny chunks (so readMore is called many times mid-scan).
    byte[] data = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\0".getBytes(StandardCharsets.US_ASCII);
    assertEquals(41, data.length);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(chunkedStream(data, 3), 16);
    assertThrows(IOException.class,
        () -> in.scanCStringLength(30, "ParameterStatus", 34));
  }

  @Test
  void getPositionTracksReadsSkipsAndCompaction() throws IOException {
    // 64-byte payload, large enough to force readMore() and compaction with an 8-byte
    // buffer, exercising both the in-buffer and out-of-buffer branches that maintain
    // the position counter.
    byte[] data = new byte[64];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) i;
    }
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(chunkedStream(data, 5), 8);

    assertEquals(0L, in.getPosition(), "fresh stream");

    // Single-byte read advances by 1 even though the buffer is filled in 5-byte chunks.
    assertEquals(0, in.read());
    assertEquals(1L, in.getPosition());

    // readInt2 / readInt4 must be reflected in the logical position.
    in.readInt2();
    assertEquals(3L, in.getPosition());
    in.readInt4();
    assertEquals(7L, in.getPosition());

    // Bulk read that crosses the buffer boundary into the wrapped stream.
    byte[] buf = new byte[20];
    int n = in.read(buf, 0, buf.length);
    assertEquals(20, n);
    assertEquals(27L, in.getPosition());

    // skip() that drains the buffer and continues on the wrapped stream.
    long skipped = in.skip(10);
    assertEquals(10L, skipped);
    assertEquals(37L, in.getPosition());
  }

  @Test
  void getPositionUnchangedByPeek() throws IOException {
    byte[] data = {1, 2, 3, 4};
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ByteArrayInputStream(data), 8);

    assertEquals(0L, in.getPosition());
    assertEquals(1, in.peek());
    assertEquals(0L, in.getPosition(), "peek() must not advance the position");
    assertEquals(1, in.read());
    assertEquals(1L, in.getPosition());
  }

  @Test
  void getPositionUnchangedByScanCStringLength() throws IOException {
    // scanCStringLength only inspects the buffer (and may pull more bytes via readMore),
    // but it must not advance the read cursor; the caller skips explicitly. This is the
    // invariant PGStream's bounded-string helpers rely on.
    byte[] data = "name\0value\0".getBytes(StandardCharsets.US_ASCII);
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(chunkedStream(data, 3), 8);

    int nameLen = in.scanCStringLength(11, "Test", 11);
    assertEquals(5, nameLen);
    assertEquals(0L, in.getPosition(), "scanCStringLength must not advance the cursor");
    in.skip(nameLen);
    assertEquals(5L, in.getPosition());
  }
}
