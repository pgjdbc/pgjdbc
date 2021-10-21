/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.List;

@RunWith(Parameterized.class)
public class UTF8EncodingTest {

  private static final int STEP = 8 * 1024;

  @Parameterized.Parameter(0)
  public Encoding encoding;

  @Parameterized.Parameter(1)
  public String string;

  @Parameterized.Parameters(name = "string={1}, encoding={0}")
  public static Iterable<Object[]> data() {
    final StringBuilder reallyLongString = new StringBuilder(1024 * 1024);
    for (int i = 0; i < 185000; ++i) {
      reallyLongString.append(i);
    }

    final List<String> strings = new ArrayList<String>(150);
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
      for (int j = 0; j < count; ++j) {
        testChars[j] = (char) (i + j);
      }

      strings.add(new String(testChars));
    }

    for (int i = 0xe000; i < 0x10000; i += STEP) {
      int count = (i + STEP) > 0x10000 ? 0x10000 - i : STEP;
      char[] testChars = new char[count];
      for (int j = 0; j < count; ++j) {
        testChars[j] = (char) (i + j);
      }

      strings.add(new String(testChars));
    }

    for (int i = 0x10000; i < 0x110000; i += STEP) {
      int count = (i + STEP) > 0x110000 ? 0x110000 - i : STEP;
      char[] testChars = new char[count * 2];
      for (int j = 0; j < count; ++j) {
        testChars[j * 2] = (char) (0xd800 + ((i + j - 0x10000) >> 10));
        testChars[j * 2 + 1] = (char) (0xdc00 + ((i + j - 0x10000) & 0x3ff));
      }

      strings.add(new String(testChars));
    }

    final List<Object[]> data = new ArrayList<Object[]>(strings.size() * 2);
    for (String string : strings) {
      data.add(new Object[] { Encoding.getDatabaseEncoding("UNICODE"), string });
    }
    return data;
  }

  @Test
  public void test() throws Exception {
    final byte[] encoded = encoding.encode(string);
    assertEquals(string, encoding.decode(encoded));
  }
}
