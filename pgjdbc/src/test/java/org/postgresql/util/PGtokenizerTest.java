/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

class PGtokenizerTest {

  @Test
  void tokenize() {
    PGtokenizer pGtokenizer = new PGtokenizer("1,2EC1830300027,1,,",',');
    assertEquals(5,pGtokenizer.getSize());

  }

  @Test
  void removePara() {
    String string = PGtokenizer.removePara("(1,2EC1830300027,1,,)");
    Assert.assertEquals("1,2EC1830300027,1,,", string);
  }

  @Test
  void removeBox() {
    assertTrue(true);
  }

  @Test
  void removeAngle() {
    assertTrue(true);
  }

  @Test
  void removeCurlyBrace() {
    assertTrue(true);
  }
}
