/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

/**
 * Cursor over a PostgreSQL container text literal (array, composite, range).
 *
 * <p>Owns the two jobs that the binary length-prefixed format does not have:
 * finding where one element ends (scanning past quotes, escapes and the
 * delimiter) and stripping the quoting/escaping so the leaf codec receives the
 * logical value. The container driver supplies the structural framing (brackets,
 * delimiter, null convention); the cursor stays policy-free so a single instance
 * serves arrays, composites and ranges.</p>
 *
 * <h2>Reading values</h2>
 *
 * <p>{@link #readValue(char, char)} consumes one element and exposes it as a borrowed
 * slice via {@link #tokenChars()} / {@link #tokenOffset()} / {@link #tokenLength()}.
 * The slice points directly into the backing {@code char[]} when the element has
 * no escapes, and into a reusable scratch buffer when it does. It is valid only
 * until the next {@code readValue} call; leaf codecs must not retain it.</p>
 *
 * <p>On the wire side this cursor is lenient on decode: inside double quotes it
 * accepts both {@code \x} backslash escapes and {@code ""} doubled quotes, which
 * covers {@code array_out}, {@code record_out} and {@code range_out}. The
 * per-container escaping differences only matter on encode, which the leaf
 * writers already own.</p>
 */
final class LiteralCursor {

  private static final char[] EMPTY = new char[0];

  private final char[] src;
  private final int start;
  private final int end;
  private int pos;

  private char[] scratch = EMPTY;

  private char[] tokenBuf;
  private int tokenOff;
  private int tokenLen;
  private boolean tokenQuoted;

  LiteralCursor(char[] src, int offset, int length) {
    this.src = src;
    this.start = offset;
    this.pos = offset;
    this.end = offset + length;
    this.tokenBuf = src;
  }

  static LiteralCursor over(String literal) {
    char[] chars = literal.toCharArray();
    return new LiteralCursor(chars, 0, chars.length);
  }

  // -------------------------- structural primitives --------------------------

  boolean atEnd() {
    return pos >= end;
  }

  char peek() {
    return pos < end ? src[pos] : '\0';
  }

  void skipWhitespace() {
    while (pos < end && isWhitespace(src[pos])) {
      pos++;
    }
  }

  /** Consumes {@code c} (after leading whitespace), or fails for a malformed literal. */
  void expect(char c) throws SQLException {
    skipWhitespace();
    if (pos >= end || src[pos] != c) {
      throw malformed(c);
    }
    pos++;
  }

  /** Consumes {@code c} if present (after leading whitespace) and reports whether it did. */
  boolean tryConsume(char c) {
    skipWhitespace();
    if (pos < end && src[pos] == c) {
      pos++;
      return true;
    }
    return false;
  }

  /**
   * Consumes {@code keyword} (case-insensitive, after leading whitespace) when it
   * appears as a complete token — followed by end-of-input or whitespace — and
   * reports whether it did. Used for the range {@code empty} literal.
   */
  boolean consumeKeyword(String keyword) {
    skipWhitespace();
    int n = keyword.length();
    if (pos + n > end) {
      return false;
    }
    for (int i = 0; i < n; i++) {
      if (Character.toLowerCase(src[pos + i]) != Character.toLowerCase(keyword.charAt(i))) {
        return false;
      }
    }
    if (pos + n < end && !isWhitespace(src[pos + n])) {
      return false;
    }
    pos += n;
    skipWhitespace();
    return true;
  }

  /** The full backing literal as a string, for diagnostics on malformed input. */
  String literal() {
    return new String(src, start, end - start);
  }

  /**
   * Skips the leading {@code [l:u]=} dimension prefix of an array literal, if any.
   * The bounds are discarded, matching the existing JDBC array behaviour.
   */
  void skipDimensionPrefix() {
    skipWhitespace();
    if (pos < end && src[pos] == '[') {
      while (pos < end && src[pos] != '=') {
        pos++;
      }
      if (pos < end) {
        pos++; // consume '='
      }
    }
  }

  /**
   * Counts the consecutive opening braces at the current position without
   * consuming them — the dimensionality of the array literal.
   */
  int countLeadingBraces() {
    int p = pos;
    int dims = 0;
    while (p < end && isWhitespace(src[p])) {
      p++;
    }
    while (p < end && src[p] == '{') {
      dims++;
      p++;
      while (p < end && isWhitespace(src[p])) {
        p++;
      }
    }
    return dims;
  }

  // -------------------------- value reading --------------------------

  /**
   * Reads one element up to (but not consuming) the next {@code delim} or the
   * container's {@code close} bracket. The decoded value is exposed via
   * {@link #tokenChars()} / {@link #tokenOffset()} / {@link #tokenLength()} and
   * {@link #tokenWasQuoted()}.
   *
   * <p>Only {@code close} terminates an unquoted run, not every bracket kind:
   * an unquoted composite field may legitimately contain {@code {}}/{@code []}
   * (for example an empty array field {@code {}}), and an unquoted array element
   * may be a literal {@code )} or {@code ]}. The server quotes any value
   * containing the container's own delimiter or close bracket, so those only
   * ever appear unquoted as structural tokens.</p>
   *
   * @param delim the element delimiter (for example {@code ','} or {@code ';'})
   * @param close the container's closing bracket ({@code '}'} for arrays,
   *     {@code ')'} for composites/ranges)
   */
  void readValue(char delim, char close) throws SQLException {
    readValue(delim, close, close);
  }

  /**
   * Variant accepting two acceptable closing brackets, for a range upper bound
   * that may be followed by either {@code ']'} (inclusive) or {@code ')'}
   * (exclusive).
   *
   * @param delim the element delimiter
   * @param close1 one acceptable closing bracket
   * @param close2 the other acceptable closing bracket
   */
  void readValue(char delim, char close1, char close2) throws SQLException {
    skipWhitespace();
    if (pos < end && src[pos] == '"') {
      pos++; // consume opening quote
      readQuoted();
      tokenQuoted = true;
      skipWhitespace();
      return;
    }
    int start = pos;
    while (pos < end) {
      char c = src[pos];
      if (c == delim || c == close1 || c == close2 || isWhitespace(c)) {
        break;
      }
      pos++;
    }
    tokenBuf = src;
    tokenOff = start;
    tokenLen = pos - start;
    tokenQuoted = false;
    skipWhitespace();
  }

  char[] tokenChars() {
    return tokenBuf;
  }

  int tokenOffset() {
    return tokenOff;
  }

  int tokenLength() {
    return tokenLen;
  }

  boolean tokenWasQuoted() {
    return tokenQuoted;
  }

  /** Reports whether the current token equals {@code s} (case-sensitive). */
  boolean tokenEquals(String s) {
    if (tokenLen != s.length()) {
      return false;
    }
    for (int i = 0; i < tokenLen; i++) {
      if (tokenBuf[tokenOff + i] != s.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  // -------------------------- structural skipping (measure pass) --------------------------

  /** Skips one scalar element (quote-aware) without materializing its value. */
  void skipScalar(char delim, char close) throws SQLException {
    skipWhitespace();
    if (pos < end && src[pos] == '"') {
      pos++;
      skipRestOfQuoted();
      skipWhitespace();
      return;
    }
    while (pos < end) {
      char c = src[pos];
      if (c == delim || c == close || isWhitespace(c)) {
        break;
      }
      pos++;
    }
    skipWhitespace();
  }

  /** Skips one balanced {@code {...}} sub-array (quote-aware). */
  void skipSubarray() throws SQLException {
    expect('{');
    int depth = 1;
    while (depth > 0) {
      if (pos >= end) {
        throw malformed('}');
      }
      char c = src[pos++];
      if (c == '"') {
        skipRestOfQuoted();
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
      }
    }
  }

  // -------------------------- internals --------------------------

  /** Assumes the opening quote was already consumed; materializes up to the closing quote. */
  private void readQuoted() throws SQLException {
    int start = pos;
    boolean needsScratch = false;
    int p = pos;
    while (p < end) {
      char c = src[p];
      if (c == '\\') {
        needsScratch = true;
        p += 2;
        continue;
      }
      if (c == '"') {
        if (p + 1 < end && src[p + 1] == '"') {
          needsScratch = true;
          p += 2;
          continue;
        }
        break;
      }
      p++;
    }
    if (p >= end) {
      throw malformed('"');
    }
    if (!needsScratch) {
      tokenBuf = src;
      tokenOff = start;
      tokenLen = p - start;
    } else {
      char[] buf = ensureScratch(p - start);
      int n = 0;
      int q = start;
      while (q < p) {
        char c = src[q];
        if (c == '\\' && q + 1 < end) {
          buf[n++] = src[q + 1];
          q += 2;
        } else if (c == '"' && q + 1 < p && src[q + 1] == '"') {
          buf[n++] = '"';
          q += 2;
        } else {
          buf[n++] = c;
          q++;
        }
      }
      tokenBuf = buf;
      tokenOff = 0;
      tokenLen = n;
    }
    pos = p + 1; // consume closing quote
  }

  /** Assumes the opening quote was already consumed; advances past the closing quote. */
  private void skipRestOfQuoted() {
    while (pos < end) {
      char c = src[pos++];
      if (c == '\\') {
        if (pos < end) {
          pos++;
        }
      } else if (c == '"') {
        if (pos < end && src[pos] == '"') {
          pos++; // doubled quote, keep scanning
        } else {
          return; // closing quote
        }
      }
    }
  }

  private char[] ensureScratch(int needed) {
    if (scratch.length < needed) {
      scratch = new char[Math.max(needed, 16)];
    }
    return scratch;
  }

  private static boolean isWhitespace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
  }

  private PSQLException malformed(char expected) {
    return new PSQLException(
        GT.tr("Malformed array/composite literal: expected ''{0}'' at offset {1}",
            expected, pos),
        PSQLState.DATA_ERROR);
  }
}
