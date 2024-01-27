/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;

public class UTF8EncodingTest {

  private static final int STEP = 8 * 1024;

  public static Iterable<Object[]> data() {
    final StringBuilder reallyLongString = new StringBuilder(1024 * 1024);
    for (int i = 0; i < 185000; i++) {
      reallyLongString.append(i);
    }

    final List<String> strings = new ArrayList<>(150);
    strings.add("short simple");
    strings.add("longer but still not really all that long");
    strings.add(reallyLongString.toString());
    strings.add(reallyLongString.append('\u03C0').toString()); // add multi-byte to end of a long string
    strings.add(reallyLongString.delete((32 * 1024) + 5, reallyLongString.capacity() - 1).toString());
    strings.add(reallyLongString.append('\u00DC').toString()); // add high order char to end of mid length string
    strings.add(reallyLongString.delete((16 * 1024) + 5, reallyLongString.capacity() - 1).toString());
    strings.add(reallyLongString.append('\u00DD').toString()); // add high order char to end of mid length string
    strings.add("e\u00E4t \u03A3 \u03C0 \u798F, it is good"); // need to test some multi-byte characters

    for (int i = 1; i < 0xd800; i += STEP) {
      int count = (i + STEP) > 0xd800 ? 0xd800 - i : STEP;
      char[] testChars = new char[count];
      for (int j = 0; j < count; j++) {
        testChars[j] = (char) (i + j);
      }

      strings.add(new String(testChars));
    }

    for (int i = 0xe000; i < 0x10000; i += STEP) {
      int count = (i + STEP) > 0x10000 ? 0x10000 - i : STEP;
      char[] testChars = new char[count];
      for (int j = 0; j < count; j++) {
        testChars[j] = (char) (i + j);
      }

      strings.add(new String(testChars));
    }

    for (int i = 0x10000; i < 0x110000; i += STEP) {
      int count = (i + STEP) > 0x110000 ? 0x110000 - i : STEP;
      char[] testChars = new char[count * 2];
      for (int j = 0; j < count; j++) {
        testChars[j * 2] = (char) (0xd800 + ((i + j - 0x10000) >> 10));
        testChars[j * 2 + 1] = (char) (0xdc00 + ((i + j - 0x10000) & 0x3ff));
      }

      strings.add(new String(testChars));
    }

    final List<Object[]> data = new ArrayList<>(strings.size() * 2);
    for (String string : strings) {
      String shortString = string;
      if (shortString != null && shortString.length() > 1000) {
        shortString = shortString.substring(0, 100) + "...(" + string.length() + " chars)";
      }
      data.add(new Object[]{Encoding.getDatabaseEncoding("UNICODE"), string, shortString});
    }
    return data;
  }

  @MethodSource("data")
  @ParameterizedTest(name = "string={2}, encoding={0}")
  void test(Encoding encoding, String string, String shortString) throws Exception {
    final byte[] encoded = encoding.encode(string);
    assertEquals(string, encoding.decode(encoded));
  }
}
