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
    // application_name\0Driver Tests\0 — 30 bytes total, the first ParameterStatus body
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
}
