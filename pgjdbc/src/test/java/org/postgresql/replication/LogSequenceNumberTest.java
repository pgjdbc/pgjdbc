/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("Replication")
class LogSequenceNumberTest {
  @Test
  void notNullWhenCreateFromStr() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf("0/15D68C50");
    assertThat(result, notNullValue());
  }

  @Test
  void parseNotValidLSNStr() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf("15D68C55");
    assertThat(result, equalTo(LogSequenceNumber.INVALID_LSN));
  }

  @Test
  void parseLSNFromStringAndConvertToLong() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf("16/3002D50");
    assertThat("64-bit number use in replication protocol, "
            + "that why we should can convert string represent LSN to long",
        result.asLong(), equalTo(94539623760L)
    );
  }

  @Test
  void convertNumericLSNToString() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf(94539623760L);

    assertThat("64-bit number use in replication protocol, "
            + "but more readable standard format use in logs where each 8-bit print in hex form via slash",
        result.asString(), equalTo("16/3002D50")
    );
  }

  @Test
  void convertNumericLSNToString_2() throws Exception {
    LogSequenceNumber result = LogSequenceNumber.valueOf(366383352L);

    assertThat("64-bit number use in replication protocol, "
            + "but more readable standard format use in logs where each 8-bit print in hex form via slash",
        result.asString(), equalTo("0/15D690F8")
    );
  }

  @Test
  void equalLSN() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D690F8");

    assertThat(first, equalTo(second));
  }

  @Test
  void equalLSNCreateByDifferentWay() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf(366383352L);

    assertThat("LSN creates as 64-bit number and as string where each 8-bit print in hex form "
            + "via slash represent same position in WAL should be equals",
        first, equalTo(second)
    );
  }

  @Test
  void notEqualLSN() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D68C50");

    assertThat(first, not(equalTo(second)));
  }

  @Test
  void differentLSNHaveDifferentHash() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D68C50");

    assertThat(first.hashCode(), not(equalTo(second.hashCode())));
  }

  @Test
  void sameLSNHaveSameHash() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D690F8");

    assertThat(first.hashCode(), equalTo(second.hashCode()));
  }

  @Test
  void compareToSameValue() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf("0/15D690F8");
    LogSequenceNumber second = LogSequenceNumber.valueOf("0/15D690F8");

    assertThat(first.compareTo(second), equalTo(0));
    assertThat(second.compareTo(first), equalTo(0));
  }

  @Test
  void compareToPositiveValues() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf(1234);
    LogSequenceNumber second = LogSequenceNumber.valueOf(4321);

    assertThat(first.compareTo(second), equalTo(-1));
    assertThat(second.compareTo(first), equalTo(1));
  }

  @Test
  void compareToNegativeValues() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf(0x8000000000000000L);
    LogSequenceNumber second = LogSequenceNumber.valueOf(0x8000000000000001L);

    assertThat(first.compareTo(second), equalTo(-1));
    assertThat(second.compareTo(first), equalTo(1));
  }

  @Test
  void compareToMixedSign() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.valueOf(1);
    LogSequenceNumber second = LogSequenceNumber.valueOf(0x8000000000000001L);

    assertThat(first.compareTo(second), equalTo(-1));
    assertThat(second.compareTo(first), equalTo(1));
  }

  @Test
  void compareToWithInvalid() throws Exception {
    LogSequenceNumber first = LogSequenceNumber.INVALID_LSN;
    LogSequenceNumber second = LogSequenceNumber.valueOf(1);

    assertThat(first.compareTo(second), equalTo(-1));
    assertThat(second.compareTo(first), equalTo(1));
  }
}
