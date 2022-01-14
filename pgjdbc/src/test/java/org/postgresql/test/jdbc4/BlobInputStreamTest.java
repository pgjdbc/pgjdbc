/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Random;

public class BlobInputStreamTest extends BaseTest4 {
  byte []buffer = new byte[1123];
  long oid;
  LargeObject largeObject;

  @Before
  public void createBlob() throws Exception {
    Random rd = new Random();
    rd.nextBytes(buffer);
    con = TestUtil.openDB();
    con.setAutoCommit(false);
    LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
    oid = lom.createLO(LargeObjectManager.READWRITE);
    largeObject = lom.open(oid);
    largeObject.write(buffer);
    // move us to the beginning
    largeObject.seek(0);

  }

  @After
  public void destroyBlob() throws Exception {
    con.setAutoCommit(false);
    LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
    lom.delete(oid);
    con.close();
  }

  /*
    read()
        mark(280)
        mark(91)
        mark(110)
        read()
        read()
        mark(1120)
        read([], 3, 40)
        read([], 23, 103)
        skip(1)
        mark(25)
        read([], 12, 5)
        mark(103)
        mark(72)
        read([], 660, 31)
        read([], 100, 307)
        read()
        read([], 137, 20)
        mark(44)
        skip(244)
        mark(442)
        skip(36)
        mark(36)
        skip(81)
        read([], 58, 276)
        read([], 452, 586)
   */
  @Test
  public void read() throws Exception {
    byte []e = new byte[1123];
    byte []a = new byte[1123];

    con.setAutoCommit(false);
    InputStream is = largeObject.getInputStream(2048, Integer.MAX_VALUE);
    BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(buffer));
    int expected;
    int actual;

    is.read();
    bis.read();

    is.mark(280);
    bis.mark(280);

    is.mark(91);
    bis.mark(91);

    is.mark(110);
    bis.mark(110);

    is.read();
    bis.read();

    is.read();
    bis.read();

    is.mark(1120);
    bis.mark(1120);

    is.read(e, 3, 40);
    bis.read(e, 3, 40);

    is.read(e, 23, 103);
    bis.read(e, 23, 103);

    is.skip(1);
    bis.skip(1);

    is.mark(25);
    bis.mark(25);

    is.read(e, 12, 5);
    bis.read(e, 12, 5);

    is.mark(103);
    bis.mark(103);

    is.mark(72);
    bis.mark(72);

    is.read(e, 660, 31);
    bis.read(e, 660, 31);

    is.read(e, 100, 307);
    bis.read(e, 100, 307);

    is.read();
    bis.read();

    is.read(e, 137, 20);
    bis.read(e, 137, 20);

    is.mark(44);
    bis.mark(44);

    is.skip(244);
    bis.skip(244);

    is.mark(442);
    bis.mark(442);

    is.skip(36);
    bis.skip(36);

    is.mark(36);
    bis.mark(36);

    is.skip(81);
    bis.skip(81);

    is.read(e, 58, 276);
    bis.read(e, 58, 276);

    expected = is.read(e, 452, 586);
    actual = bis.read(e, 452, 586);
    assertEquals(expected, actual);

    is.mark(0);
    bis.mark(0);

    expected = bis.read(e, 0, 0);
    actual = is.read(a,0,0);
    assertEquals(expected, actual);

    expected = bis.read();
    actual = is.read();
    assertEquals(expected, actual);

    expected = bis.read(e, 0, 1);
    actual = is.read(a,0,1);
    assertEquals(expected, actual);

    expected = bis.read(e, 0, 256);
    actual = is.read(a,0,256);
    assertEquals(expected, actual);

    expected = bis.read();
    actual = is.read();
    assertEquals(expected, actual);

  }
}
