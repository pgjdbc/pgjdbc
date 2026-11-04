/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.core.Oid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.stream.Stream;

/**
 * A Bind trace is never longer than {@code maxLogMessageLength}, never ends in the middle of a
 * parameter, says when a value is shortened or a parameter is left out, and with no limit
 * matches the format logged before the limit existed.
 */
class BindLogTest {
  /** A character outside the Basic Multilingual Plane, written as a surrogate pair. */
  private static final String EMOJI = new String(Character.toChars(0x1F600));

  private static final String TWO_INTS =
      " FE=> Bind(stmt=S_1,portal=null,$1=<('1'::int4)>,type=INT4,$2=<('2'::int4)>,type=INT4)";

  private static final TypeTransferModeRegistry TEXT_ONLY = new TypeTransferModeRegistry() {
    @Override
    public boolean useBinaryForSend(int oid) {
      return false;
    }

    @Override
    public boolean useBinaryForReceive(int oid) {
      return false;
    }
  };

  private static SimpleParameterList params(int count) {
    return new SimpleParameterList(count, TEXT_ONLY);
  }

  private static String repeat(String s, int count) {
    StringBuilder sb = new StringBuilder(s.length() * count);
    for (int i = 0; i < count; i++) {
      sb.append(s);
    }
    return sb.toString();
  }

  private static SimpleParameterList twoInts() throws SQLException {
    SimpleParameterList params = params(2);
    params.setIntParameter(1, 1);
    params.setIntParameter(2, 2);
    return params;
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 86, 16_384})
  void aTraceThatFitsMatchesTheFormatWithoutALimit(int limit) throws SQLException {
    assertEquals(86, TWO_INTS.length());
    assertEquals(TWO_INTS, BindLog.format("S_1", null, twoInts(), true, limit));
  }

  @Test
  void oneCharacterBelowTheFullLengthLeavesOutTheLastParameterWithAMarker() throws SQLException {
    assertEquals(" FE=> Bind(stmt=S_1,portal=null,$1=<('1'::int4)>,type=INT4...(truncated)",
        BindLog.format("S_1", null, twoInts(), true, 85));
  }

  static Stream<Arguments> threeVarcharLimits() {
    return Stream.of(
        arguments(74, " FE=> Bind(stmt=S_dangle,portal=null...(truncated)"),
        arguments(75, " FE=> Bind(stmt=S_dangle,portal=null,$1=<('a...>,type=VARCHAR...(truncated)"),
        arguments(80, " FE=> Bind(stmt=S_dangle,portal=null,$1=<('aaaaaa...>,type=VARCHAR...(truncated)"),
        arguments(112, " FE=> Bind(stmt=S_dangle,portal=null,$1=<('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa...>,type=VARCHAR...(truncated)"),
        arguments(113, " FE=> Bind(stmt=S_dangle,portal=null,$1=<('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')>,type=VARCHAR...(truncated)"),
        arguments(137, " FE=> Bind(stmt=S_dangle,portal=null,$1=<('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')>,type=VARCHAR...(truncated)"),
        arguments(138, " FE=> Bind(stmt=S_dangle,portal=null,$1=<('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')>,type=VARCHAR,$2=<('bb')>,type=VARCHAR...(truncated)"),
        arguments(149, " FE=> Bind(stmt=S_dangle,portal=null,$1=<('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')>,type=VARCHAR,$2=<('bb')>,type=VARCHAR...(truncated)"),
        arguments(150, " FE=> Bind(stmt=S_dangle,portal=null,$1=<('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')>,type=VARCHAR,$2=<('bb')>,type=VARCHAR,$3=<('cc')>,type=VARCHAR)")
    );
  }

  @ParameterizedTest
  @MethodSource("threeVarcharLimits")
  void aTraceIsCutOnlyBetweenParametersOrInsideAStringValue(int limit, String expected)
      throws SQLException {
    SimpleParameterList params = params(3);
    params.setStringParameter(1, repeat("a", 40), Oid.VARCHAR);
    params.setStringParameter(2, "bb", Oid.VARCHAR);
    params.setStringParameter(3, "cc", Oid.VARCHAR);
    assertEquals(expected, BindLog.format("S_dangle", null, params, true, limit));
  }

  @Test
  void aVarcharThatFitsIsShownWholeWithoutACast() throws SQLException {
    SimpleParameterList params = params(2);
    params.setStringParameter(1, repeat("v", 60), Oid.VARCHAR);
    params.setIntParameter(2, 7);
    String expected = " FE=> Bind(stmt=null,portal=null,$1=<('" + repeat("v", 60)
        + "')>,type=VARCHAR,$2=<('7'::int4)>,type=INT4)";
    assertEquals(143, expected.length());
    assertEquals(expected, BindLog.format(null, null, params, true, 143));
  }

  @Test
  void aLongStringKeepsAPrefixEndingWithEllipsis() throws SQLException {
    SimpleParameterList params = params(1);
    params.setStringParameter(1, "PREFIX-" + repeat("H", 200_000), Oid.VARCHAR);
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<('PREFIX-...>,type=VARCHAR)",
        BindLog.format(null, null, params, true, 64));
  }

  @Test
  void aCutNeverSplitsASurrogatePair() throws SQLException {
    SimpleParameterList params = params(1);
    params.setStringParameter(1, repeat(EMOJI, 50), Oid.VARCHAR);
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<('" + EMOJI + "...>,type=VARCHAR)",
        BindLog.format(null, null, params, true, 60));
  }

  @Test
  void aCutNeverSplitsADoubledQuote() throws SQLException {
    SimpleParameterList params = params(1);
    params.setStringParameter(1, "ab" + repeat("'", 100), Oid.VARCHAR);
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<('ab''...>,type=VARCHAR)",
        BindLog.format(null, null, params, true, 62));
  }

  @Test
  void aCutNeverSplitsADoubledBackslashWithoutStandardConformingStrings() throws SQLException {
    SimpleParameterList params = params(1);
    params.setStringParameter(1, "a\\" + repeat("d", 100), Oid.VARCHAR);
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<('a...>,type=VARCHAR)",
        BindLog.format(null, null, params, false, 59));
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<('a\\\\...>,type=VARCHAR)",
        BindLog.format(null, null, params, false, 60));
  }

  @Test
  void manyParametersAreLeftOutWithAMarker() throws SQLException {
    SimpleParameterList params = params(500);
    for (int i = 1; i <= 500; i++) {
      params.setStringParameter(i, "value-" + i + "-" + repeat("x", 80), Oid.VARCHAR);
    }
    StringBuilder expected = new StringBuilder(" FE=> Bind(stmt=S_many,portal=null");
    for (int i = 1; i <= 4; i++) {
      expected.append(",$").append(i).append("=<('value-").append(i).append('-')
          .append(repeat("x", 80)).append("')>,type=VARCHAR");
    }
    expected.append("...(truncated)");
    assertEquals(expected.toString(), BindLog.format("S_many", null, params, true, 512));
  }

  @ParameterizedTest
  @ValueSource(ints = {59, 72})
  void aLimitTooSmallForOneParameterKeepsOnlyTheHeader(int limit) throws SQLException {
    SimpleParameterList params = params(30);
    for (int i = 1; i <= 30; i++) {
      params.setIntParameter(i, i);
    }
    assertEquals(" FE=> Bind(stmt=null,portal=null...(truncated)",
        BindLog.format(null, null, params, true, limit));
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<('1'::int4)>,type=INT4...(truncated)",
        BindLog.format(null, null, params, true, 73));
  }

  @Test
  void aByteaThatDoesNotFitShowsItsSizeAndKeepsLaterParameters() throws SQLException {
    SimpleParameterList params = params(2);
    params.setBytea(1, new byte[10_000], 0, 10_000);
    params.setIntParameter(2, 42);
    assertEquals(" FE=> Bind(stmt=S_bytea,portal=null,$1=<<10000 bytes>>,type=BYTEA,"
            + "$2=<('42'::int4)>,type=INT4)",
        BindLog.format("S_bytea", null, params, true, 16_384));
  }

  @Test
  void aByteaThatFitsIsShownAsAHexLiteral() throws SQLException {
    SimpleParameterList params = params(1);
    params.setBytea(1, new byte[]{1, (byte) 0xab}, 0, 2);
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<'\\x01ab'::bytea>,type=BYTEA)",
        BindLog.format(null, null, params, true, 16_384));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 120, 16_384})
  void aByteaStreamIsShownTheSameWayAtEveryLimit(int limit) throws SQLException {
    SimpleParameterList params = params(1);
    params.setBytea(1, new ByteArrayInputStream(new byte[100]), 100);
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<?>,type=BYTEA)",
        BindLog.format(null, null, params, true, limit));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 120, 16_384})
  void aTextStreamIsShownTheSameWayAtEveryLimit(int limit) throws SQLException {
    SimpleParameterList params = params(1);
    params.setText(1, new ByteArrayInputStream(new byte[100]));
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<('<stream of 100 bytes>')>,type=TEXT)",
        BindLog.format(null, null, params, true, limit));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 120, 16_384})
  void anUnsetParameterIsShownAsAQuestionMark(int limit) throws SQLException {
    SimpleParameterList params = params(2);
    params.setIntParameter(2, 5);
    assertEquals(" FE=> Bind(stmt=null,portal=null,$1=<?>,type=UNSPECIFIED,"
            + "$2=<('5'::int4)>,type=INT4)",
        BindLog.format(null, null, params, true, limit));
  }

  @Test
  void aSqlNullIsShownAsNull() throws SQLException {
    SimpleParameterList params = params(1);
    params.setNull(1, Oid.VARCHAR);
    assertEquals(" FE=> Bind(stmt=S_null,portal=null,$1=<(NULL)>,type=VARCHAR)",
        BindLog.format("S_null", null, params, true, 16_384));
  }

  @Test
  void aValueOfUnknownTypeIsNotRenderedWhenALimitIsSet() throws Exception {
    SimpleParameterList params = params(1);
    boolean[] rendered = new boolean[1];
    Object unknown = new Object() {
      @Override
      public String toString() {
        rendered[0] = true;
        return "unknown";
      }
    };
    Field paramValues = SimpleParameterList.class.getDeclaredField("paramValues");
    paramValues.setAccessible(true);
    ((Object[]) paramValues.get(params))[0] = unknown;

    assertEquals(" FE=> Bind(stmt=null,portal=null...(truncated)",
        BindLog.format(null, null, params, true, 256));
    assertFalse(rendered[0], "toString of a value of unknown type must not be called");
  }

  @Test
  void noLimitKeepsTheWholeTrace() throws SQLException {
    SimpleParameterList params = params(1);
    String value = repeat("z", 20_000);
    params.setStringParameter(1, value, Oid.VARCHAR);
    assertEquals(" FE=> Bind(stmt=S_full,portal=null,$1=<('" + value + "')>,type=VARCHAR)",
        BindLog.format("S_full", null, params, true, 0));
  }
}
