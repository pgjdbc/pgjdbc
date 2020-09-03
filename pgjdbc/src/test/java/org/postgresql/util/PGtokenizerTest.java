package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.*;

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
  }

  @Test
  void removeAngle() {
  }

  @Test
  void removeCurlyBrace() {
  }
}
