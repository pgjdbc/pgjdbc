/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import org.junit.Test;


public class LogSequenceNumberTest {
  @Test
  public void testNotNullWhenCreateFromStr() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf("0/15D68C50");
    assertThat(result, notNullValue());
  }

  @Test
  public void testParseNotValidLSNStr() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf("15D68C55");
    assertThat(result, equalTo(LogSequenceNumber.INVALID_LSN));
  }

  @Test
  public void testParseLSNFromStringAndConvertToLong() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf("16/3002D50");
    assertThat("64-bit number use in replication protocol, "
            + "that why we should can convert string represent LSN to long",
        result.asLong(), equalTo(94539623760L)
    );
  }

  @Test
  public void testConvertNumericLSNToString() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf(94539623760L);

    assertThat("64-bit number use in replication protocol, "
            + "but more readable standard format use in logs where each 8-bit print in hex form via slash",
        result.asString(), equalTo("16/3002D50")
    );
  }

  @Test
  public void testConvertNumericLSNToString_2() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf(366383352L);

    assertThat("64-bit number use in replication protocol, "
            + "but more readable standard format use in logs where each 8-bit print in hex form via slash",
        result.asString(), equalTo("0/15D690F8")
    );
  }

  @Test
  public void testEqualLSN() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D690F8");

    assertThat(first, equalTo(second));
  }

  @Test
  public void testEqualLSNCreateByDifferentWay() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf(366383352L);

    assertThat("LSN creates as 64-bit number and as string where each 8-bit print in hex form "
            + "via slash represent same position in WAL should be equals",
        first, equalTo(second)
    );
  }

  @Test
  public void testNotEqualLSN() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D68C50");

    assertThat(first, not(equalTo(second)));
  }

  @Test
  public void testDifferentLSNHaveDifferentHash() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D68C50");

    assertThat(first.hashCode(), not(equalTo(second.hashCode())));
  }

  @Test
  public void testSameLSNHaveSameHash() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D690F8");

    assertThat(first.hashCode(), equalTo(second.hashCode()));
  }
}
