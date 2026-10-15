/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/**
 * @author Thomas Kellerer
 */
public class PgByteaBlobTest {

  public PgByteaBlobTest() {
  }

  @Test
  public void testLength() throws Exception {
    byte[] data = new byte[]{1, 2, 3, 4};
    PgByteaBlob blob = new PgByteaBlob(data);
    assertEquals(data.length, blob.length());
  }

  @Test
  public void testGetBytes() throws Exception {
    byte[] data = new byte[]{1, 2, 3, 4};
    PgByteaBlob blob = new PgByteaBlob(data);
    assertArrayEquals(data, blob.getBytes(1, data.length));
  }

  @Test
  public void testSetBytes1() throws Exception {
    byte[] data = new byte[]{1, 2, 3, 4};
    PgByteaBlob blob = new PgByteaBlob(data);
    blob.setBytes(2, new byte[]{0, 0});
    byte[] newData = blob.getBytes();
    assertEquals(4, newData.length);
    assertEquals(1, newData[0]);
    assertEquals(0, newData[1]);
    assertEquals(0, newData[2]);
    assertEquals(4, newData[3]);

    blob = new PgByteaBlob(data);
    blob.setBytes(1, new byte[]{10, 11, 12, 13, 14, 15, 16});
    newData = blob.getBytes();
    assertEquals(7, newData.length);
    for (int i = 0; i < newData.length; i++) {
      assertEquals(10 + i, newData[i]);
    }
  }

  @Test
  public void testSetBytes2() throws Exception {
    byte[] data = new byte[]{1, 2, 3, 4};
    PgByteaBlob blob = new PgByteaBlob(data);

    byte[] data2 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    blob.setBytes(1, data2, 4, 1);
    byte[] current = blob.getBytes();
    assertEquals(data.length, current.length);
    assertEquals(5, current[0]);

    byte[] data3 = new byte[]{10, 11, 12, 13, 14, 15, 16};
    blob = new PgByteaBlob(new byte[]{1, 2, 3, 4});
    blob.setBytes(2, data3, 0, 7);
    current = blob.getBytes();
    assertEquals(8, current.length);
    assertEquals(1, current[0]);
    for (int i = 1; i < current.length; i++) {
      assertEquals(10 + i - 1, current[i]);
    }
  }

  @Test
  public void testPosition1()
      throws Exception {

    PgByteaBlob blob = new PgByteaBlob(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
    long pos = blob.position(new byte[]{3, 4, 5}, 1);
    assertEquals(3, pos);

    pos = blob.position(new byte[]{11, 4, 5}, 1);
    assertEquals(-1, pos);

    pos = blob.position(new byte[]{1, 2, 3}, 3);
    assertEquals(-1, pos);

    pos = blob.position(new byte[]{9, 10}, 1);
    assertEquals(9, pos);

    pos = blob.position(new byte[]{9, 10}, 5);
    assertEquals(9, pos);

    pos = blob.position(new byte[]{8, 9, 10}, 9);
    assertEquals(-1, pos);
  }

  @Test
  public void testTruncate() throws Exception {
    byte[] data = new byte[]{1, 2, 3, 4};
    PgByteaBlob blob = new PgByteaBlob(data);
    blob.truncate(2);
    assertEquals(2, blob.length());
  }

}
