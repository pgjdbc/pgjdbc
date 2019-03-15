package org.postgresql.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(Parameterized.class)
public class UTF8EncodingTest {

  @Parameterized.Parameter(0)
  public Encoding encoding;

  @Parameterized.Parameter(1)
  public String string;

  @Parameterized.Parameters(name = "string={1}, encoding={0}")
  public static Iterable<Object[]> data() {
    final StringBuilder reallyLongString = new StringBuilder(1024 * 1024);
    for (int i=0; i<185000; ++i) {
      reallyLongString.append(i);
    }

    final String[] strings = new String[] {
        "short simple",
        "longer but still not really all that long",
        reallyLongString.toString(),
        "eat \u03A3 \u03C0"
    };

    final List<Object[]> data = new ArrayList<>(strings.length * 2);
    for (Encoding encoding : Arrays.asList(new CharOptimizedUTF8Encoding(), new ByteOptimizedUTF8Encoding())) {
      for (String string : strings) {
        data.add(new Object[] {encoding, string});
      }
    }
    return data;
  }

  @Test
  public void test() throws Exception {
    final byte[] encoded = encoding.encode(string);
    assertEquals(string, encoding.decode(encoded));
  }
}
