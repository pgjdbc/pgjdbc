/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.nio.charset.Charset;

/**
 * Constant definitions for the standard {@link java.nio.charset.Charset Charsets}. These
 * charsets are guaranteed to be available on every implementation of the Java platform.
 * <p>
 * For use until the driver drop support for Java 6. With Java 7 and up
 * simply replace this with {@link java.nio.charset.StandardCharsets}
 */
public final class StandardCharsets {

  private StandardCharsets() {
  }

  /**
   * Seven-bit ASCII, a.k.a. ISO646-US, a.k.a. the Basic Latin block of the
   * Unicode character set
   */
  public static final Charset US_ASCII = Charset.forName("US-ASCII");

  /**
   * ISO Latin Alphabet No. 1, a.k.a. ISO-LATIN-1
   */
  public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

  /**
   * Eight-bit UCS Transformation Format
   */
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  /**
   * Sixteen-bit UCS Transformation Format, big-endian byte order
   */
  public static final Charset UTF_16BE = Charset.forName("UTF-16BE");

  /**
   * Sixteen-bit UCS Transformation Format, little-endian byte order
   */
  public static final Charset UTF_16LE = Charset.forName("UTF-16LE");

  /**
   * Sixteen-bit UCS Transformation Format, byte order identified by an
   * optional byte-order mark
   */
  public static final Charset UTF_16 = Charset.forName("UTF-16");
}
