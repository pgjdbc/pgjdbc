/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.NativeQuery;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;

public class NativeQueryBindLengthTest {
  @ParameterizedTest(name = "{0} == {1}")
  @MethodSource("data")
  public void testBindLengthCalculation(String name, int expected, int bindCount) {
    assertEquals(expected, NativeQuery.calculateBindLength(bindCount));
  }

  public static Iterable<Object[]> data() {
    List<Object[]> res = new ArrayList<>();
    res.add(new Object[]{"'$1'.length = 2", 2, 1});
    res.add(new Object[]{"'$1$2...$9'.length = 2*9", 18, 9});
    res.add(new Object[]{"'$1$2...$9$10'.length = 2*9+3", 21, 10});
    res.add(new Object[]{"'$1$2...$9$10..$99'.length = 2*9+3*90", 288, 99});
    res.add(new Object[]{"'$1$2...$9$10..$99$100'.length = 2*9+3*90+4", 292, 100});
    res.add(new Object[]{"'$1$2...$9$10..$99$100$101'.length = 2*9+3*90+4+4", 296, 101});
    res.add(new Object[]{"'$1...$999'.length", 3888, 999});
    res.add(new Object[]{"'$1...$1000'.length", 3893, 1000});
    res.add(new Object[]{"'$1...$9999'.length", 48888, 9999});
    res.add(new Object[]{"'$1...$10000'.length", 48894, 10000});
    res.add(new Object[]{"'$1...$32767'.length", 185496, Short.MAX_VALUE});
    return res;
  }
}
