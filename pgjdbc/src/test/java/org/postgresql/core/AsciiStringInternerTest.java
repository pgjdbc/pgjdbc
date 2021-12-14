/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.SlowTests;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 *
 * @author Brett Okken
 */
public class AsciiStringInternerTest {

  @Test
  public void testCanonicalValue() throws Exception {
    AsciiStringInterner interner = new AsciiStringInterner();
    String s1 = "testCanonicalValue";
    byte[] bytes = s1.getBytes(StandardCharsets.US_ASCII);
    String interned = interner.getString(bytes, 0, bytes.length, null);

    //interned value should be equal
    assertEquals(s1, interned);
    //but should be different instance
    assertNotSame(s1, interned);
    //asking for it again, however should return same instance
    assertSame(interned, interner.getString(bytes, 0, bytes.length, null));

    //now show that we can get the value back from a different byte[]
    byte[] bytes2 = new byte[128];
    System.arraycopy(bytes, 0, bytes2, 73, bytes.length);
    assertSame(interned, interner.getString(bytes2, 73, bytes.length, null));

    //now we will mutate the original byte[] to show that does not affect the map
    Arrays.fill(bytes, (byte) 13);
    assertSame(interned, interner.getString(bytes2, 73, bytes.length, null));
  }

  @Test
  public void testStagedValue() throws Exception {
    AsciiStringInterner interner = new AsciiStringInterner();
    String s1 = "testStagedValue";
    interner.putString(s1);
    byte[] bytes = s1.getBytes(StandardCharsets.US_ASCII);
    String interned = interner.getString(bytes, 0, bytes.length, null);
    // should be same instance
    assertSame(s1, interned);
    //asking for it again should also return same instance
    assertSame(s1, interner.getString(bytes, 0, bytes.length, null));

    //now show that we can get the value back from a different byte[]
    byte[] bytes2 = new byte[128];
    System.arraycopy(bytes, 0, bytes2, 73, bytes.length);
    assertSame(s1, interner.getString(bytes2, 73, bytes.length, null));
  }

  @Test
  public void testNonAsciiValue() throws Exception {
    final Encoding encoding = Encoding.getJVMEncoding("UTF-8");
    AsciiStringInterner interner = new AsciiStringInterner();
    String s1 = "testNonAsciiValue" + '\u03C0'; // add multi-byte to string to make invalid for intern
    byte[] bytes = s1.getBytes(StandardCharsets.UTF_8);
    String interned = interner.getString(bytes, 0, bytes.length, encoding);

    //interned value should be equal
    assertEquals(s1, interned);
    //but should be different instance
    assertNotSame(s1, interned);
    //asking for it again should again return a different instance
    final String interned2 = interner.getString(bytes, 0, bytes.length, encoding);
    assertEquals(s1, interned2);
    assertNotSame(s1, interned2);
    assertNotSame(interned, interned2);
  }

  @Test
  public void testToString() throws Exception {
    AsciiStringInterner interner = new AsciiStringInterner();
    assertEquals("empty", "AsciiStringInterner []", interner.toString());
    interner.putString("s1");
    assertEquals("empty", "AsciiStringInterner ['s1']", interner.toString());
    interner.getString("s2".getBytes(StandardCharsets.US_ASCII), 0, 2, null);
    assertEquals("empty", "AsciiStringInterner ['s1', 's2']", interner.toString());
  }

  @Test
  @Category(SlowTests.class)
  public void testGarbageCleaning() throws Exception {
    final byte[] bytes = new byte[100000];
    for (int i = 0; i < 100000; ++i) {
      bytes[i] = (byte) ThreadLocalRandom.current().nextInt(128);
    }
    final AsciiStringInterner interner = new AsciiStringInterner();
    final LongAdder length = new LongAdder();
    final Callable<Void> c = () -> {
      for (int i = 0; i < 25000; ++i) {
        String str;
        try {
          str = interner.getString(bytes, 0, ThreadLocalRandom.current().nextInt(1000, bytes.length), null);
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
        length.add(str.length());
      }
      return null;
    };
    final ExecutorService exec = Executors.newCachedThreadPool();
    try {
      exec.invokeAll(Arrays.asList(c, c, c, c));
    } finally {
      exec.shutdown();
    }
    //this is really just done to make sure java cannot tell that nothing is really being done
    assertTrue(length.sum() > 0);
  }
}
