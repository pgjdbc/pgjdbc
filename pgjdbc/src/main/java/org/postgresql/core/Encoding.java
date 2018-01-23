/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Representation of a particular character encoding.
 */
public class Encoding {

  private static final Logger LOGGER = Logger.getLogger(Encoding.class.getName());

  /**
   * String with all integer related chars.
   */
  private static final String NUMBER_TEST_STRING = "-0123456789";

  /**
   * Binary representation of {@link #NUMBER_TEST_STRING} in ascii.
   */
  private static final byte[] NUMBER_TEST_BYTES;

  static {
    try {
      NUMBER_TEST_BYTES = NUMBER_TEST_STRING.getBytes("ascii");
    } catch (UnsupportedEncodingException e) {
      //ascii must be supported
      throw new java.lang.AssertionError(e);
    }
  }

  private static final Encoding DEFAULT_ENCODING = new Encoding();
  private static final Encoding UTF8_ENCODING = new Encoding("UTF-8", true);

  /**
   * Custom utf-8 decoding is faster than jre until java 9.
   */
  private static final boolean USE_UTF8_CUSTOM_CLASS;

  static {
    final String version = System.getProperty("java.version", "1.6");
    USE_UTF8_CUSTOM_CLASS = version.startsWith("1.");
  }

  /*
   * Preferred JVM encodings for backend encodings.
   */
  private static final HashMap<String, String[]> encodings = new HashMap<String, String[]>(48);

  static {
    //Note: this list should match the set of supported server
    // encodings found in backend/util/mb/encnames.c
    encodings.put("SQL_ASCII", new String[]{"ASCII", "US-ASCII"});
    encodings.put("UNICODE", new String[]{"UTF-8", "UTF8"});
    encodings.put("UTF8", new String[]{"UTF-8", "UTF8"});
    encodings.put("LATIN1", new String[]{"ISO8859_1"});
    encodings.put("LATIN2", new String[]{"ISO8859_2"});
    encodings.put("LATIN3", new String[]{"ISO8859_3"});
    encodings.put("LATIN4", new String[]{"ISO8859_4"});
    encodings.put("ISO_8859_5", new String[]{"ISO8859_5"});
    encodings.put("ISO_8859_6", new String[]{"ISO8859_6"});
    encodings.put("ISO_8859_7", new String[]{"ISO8859_7"});
    encodings.put("ISO_8859_8", new String[]{"ISO8859_8"});
    encodings.put("LATIN5", new String[]{"ISO8859_9"});
    encodings.put("LATIN7", new String[]{"ISO8859_13"});
    encodings.put("LATIN9", new String[]{"ISO8859_15_FDIS"});
    encodings.put("EUC_JP", new String[]{"EUC_JP"});
    encodings.put("EUC_CN", new String[]{"EUC_CN"});
    encodings.put("EUC_KR", new String[]{"EUC_KR"});
    encodings.put("JOHAB", new String[]{"Johab"});
    encodings.put("EUC_TW", new String[]{"EUC_TW"});
    encodings.put("SJIS", new String[]{"MS932", "SJIS"});
    encodings.put("BIG5", new String[]{"Big5", "MS950", "Cp950"});
    encodings.put("GBK", new String[]{"GBK", "MS936"});
    encodings.put("UHC", new String[]{"MS949", "Cp949", "Cp949C"});
    encodings.put("TCVN", new String[]{"Cp1258"});
    encodings.put("WIN1256", new String[]{"Cp1256"});
    encodings.put("WIN1250", new String[]{"Cp1250"});
    encodings.put("WIN874", new String[]{"MS874", "Cp874"});
    encodings.put("WIN", new String[]{"Cp1251"});
    encodings.put("ALT", new String[]{"Cp866"});
    // We prefer KOI8-U, since it is a superset of KOI8-R.
    encodings.put("KOI8", new String[]{"KOI8_U", "KOI8_R"});
    // If the database isn't encoding-aware then we can't have
    // any preferred encodings.
    encodings.put("UNKNOWN", new String[0]);
    // The following encodings do not have a java equivalent
    encodings.put("MULE_INTERNAL", new String[0]);
    encodings.put("LATIN6", new String[0]);
    encodings.put("LATIN8", new String[0]);
    encodings.put("LATIN10", new String[0]);
  }

  private final Charset charset;
  private final String encoding;
  private final boolean fastASCIINumbers;

  /**
   * Uses the default charset of the JVM.
   */
  private Encoding() {
    this(Charset.defaultCharset().name());
  }

  /**
   * Use the charset passed as parameter.
   *
   * @param encoding charset name to use
   */
  protected Encoding(String encoding) {
    this(encoding, testAsciiNumbers(encoding));
  }

  /**
   * Constructor which allows {@link UTF8Encoding} to specify that fast ascii numbers are supported without a test.
   */
  Encoding(String encoding, boolean fastASCIINumbers) {
    if (encoding == null) {
      throw new NullPointerException("Null encoding charset not supported");
    }
    this.encoding = encoding;
    this.charset = Charset.forName(encoding);
    this.fastASCIINumbers = fastASCIINumbers;
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, "Creating new Encoding {0} with fastASCIINumbers {1}",
          new Object[]{encoding, fastASCIINumbers});
    }
  }

  /**
   * Returns true if this encoding has characters '-' and '0'..'9' in exactly same posision as
   * ascii.
   *
   * @return true if the bytes can be scanned directly for ascii numbers.
   */
  public boolean hasAsciiNumbers() {
    return fastASCIINumbers;
  }

  /**
   * Construct an Encoding for a given JVM encoding.
   *
   * @param jvmEncoding the name of the JVM encoding
   * @return an Encoding instance for the specified encoding, or an Encoding instance for the
   * default JVM encoding if the specified encoding is unavailable.
   */
  public static Encoding getJVMEncoding(String jvmEncoding) {
    if ("UTF-8".equals(jvmEncoding)) {
      return USE_UTF8_CUSTOM_CLASS ? new UTF8Encoding() : UTF8_ENCODING;
    }
    if (Charset.isSupported(jvmEncoding)) {
      return new Encoding(jvmEncoding);
    }

    return DEFAULT_ENCODING;
  }

  /**
   * Construct an Encoding for a given database encoding.
   *
   * @param databaseEncoding the name of the database encoding
   * @return an Encoding instance for the specified encoding, or an Encoding instance for the
   * default JVM encoding if the specified encoding is unavailable.
   */
  public static Encoding getDatabaseEncoding(String databaseEncoding) {
    if ("UTF8".equals(databaseEncoding)) {
      return UTF8_ENCODING;
    }
    // If the backend encoding is known and there is a suitable
    // encoding in the JVM we use that. Otherwise we fall back
    // to the default encoding of the JVM.
    String[] candidates = encodings.get(databaseEncoding);
    if (candidates != null) {
      for (String candidate : candidates) {
        LOGGER.log(Level.FINEST, "Search encoding candidate {0}", candidate);
        if (Charset.isSupported(candidate)) {
          return new Encoding(candidate);
        }
      }
    }

    // Try the encoding name directly -- maybe the charset has been
    // provided by the user.
    if (Charset.isSupported(databaseEncoding)) {
      return new Encoding(databaseEncoding);
    }

    // Fall back to default JVM encoding.
    LOGGER.log(Level.FINEST, "{0} encoding not found, returning default encoding", databaseEncoding);
    return DEFAULT_ENCODING;
  }

  /**
   * Get the name of the (JVM) encoding used.
   *
   * @return the JVM encoding name used by this instance.
   */
  public String name() {
    return charset.name();
  }

  /**
   * Encode a string to an array of bytes.
   *
   * @param s the string to encode
   * @return a bytearray containing the encoded string
   * @throws IOException if something goes wrong
   */
  public byte[] encode(String s) throws IOException {
    if (s == null) {
      return null;
    }

    return s.getBytes(charset);
  }

  /**
   * Decode an array of bytes into a string.
   *
   * @param encodedString a byte array containing the string to decode
   * @param offset        the offset in <code>encodedString</code> of the first byte of the encoded
   *                      representation
   * @param length        the length, in bytes, of the encoded representation
   * @return the decoded string
   * @throws IOException if something goes wrong
   */
  public String decode(byte[] encodedString, int offset, int length) throws IOException {
    return new String(encodedString, offset, length, charset);
  }

  /**
   * Decode an array of bytes into a string.
   *
   * @param encodedString a byte array containing the string to decode
   * @return the decoded string
   * @throws IOException if something goes wrong
   */
  public String decode(byte[] encodedString) throws IOException {
    return decode(encodedString, 0, encodedString.length);
  }

  /**
   * Get a Reader that decodes the given InputStream using this encoding.
   *
   * @param in the underlying stream to decode from
   * @return a non-null Reader implementation.
   * @throws IOException if something goes wrong
   */
  public Reader getDecodingReader(InputStream in) throws IOException {
    return new InputStreamReader(in, charset);
  }

  /**
   * Get a Writer that encodes to the given OutputStream using this encoding.
   *
   * @param out the underlying stream to encode to
   * @return a non-null Writer implementation.
   * @throws IOException if something goes wrong
   */
  public Writer getEncodingWriter(OutputStream out) throws IOException {
    return new OutputStreamWriter(out, charset);
  }

  /**
   * Get an Encoding using the default encoding for the JVM.
   *
   * @return an Encoding instance
   */
  public static Encoding defaultEncoding() {
    return DEFAULT_ENCODING;
  }

  public String toString() {
    return encoding;
  }

  /**
   * Checks weather this encoding is compatible with ASCII for the number characters '-' and
   * '0'..'9'. Where compatible means that they are encoded with exactly same values.
   *
   * @return If faster ASCII number parsing can be used with this encoding.
   */
  private static boolean testAsciiNumbers(final String encoding) {
    // TODO: test all postgres supported encoding to see if there are
    // any which do _not_ have ascii numbers in same location
    // at least all the encoding listed in the encodings hashmap have
    // working ascii numbers
    try {
      final byte[] bytes = "-0123456789".getBytes(encoding);
      return Arrays.equals(NUMBER_TEST_BYTES, bytes);
    } catch (IOException e) {
      return false;
    }
  }
}
