/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.core.SqlCommandType;

import org.junit.jupiter.api.Test;

class ResultWrapperTest {

  @Test
  void GivenNoProducingResultWrapper_WhenAppendNoProducingResult_ThenReturnAppendedInQuietMode() {
    //Given
    boolean quietMode = true;
    ResultWrapper original = new ResultWrapper(null, SqlCommandType.BEGIN, quietMode);
    ResultWrapper toAppend = new ResultWrapper(null, SqlCommandType.SET, quietMode);
    //When
    ResultWrapper appended = original.append(toAppend);
    //Then
    assertEquals(toAppend, appended, "The appended result should be returned");
    assertNull(appended.getNext(), "The appended result should not have a next result");
  }

  @Test
  void GivenNoProducingResultWrapper_WhenAppendNoProducingResult_ThenAppendItInNonQuietMode() {
    //Given
    boolean quietMode = false;
    ResultWrapper original = new ResultWrapper(null, SqlCommandType.BEGIN, quietMode);
    ResultWrapper toAppend = new ResultWrapper(null, SqlCommandType.SET, quietMode);
    //When
    ResultWrapper appended = original.append(toAppend);
    //Then
    assertNotEquals(toAppend, appended, "The appended result should not be returned");
    assertNotNull(appended.getNext(), "The appended result should have a next result");
    assertEquals(toAppend, appended.getNext(), "The appended result should be the next result");
  }

  @Test
  void GivenProducingResultWrapper_WhenAppendNoProducingResult_ThenReturnThisInQuietMode() {
    //Given
    boolean quietMode = true;
    ResultWrapper original = new ResultWrapper(null, SqlCommandType.SELECT, quietMode);
    ResultWrapper toAppend = new ResultWrapper(null, SqlCommandType.SET, quietMode);
    //When
    ResultWrapper appended = original.append(toAppend);
    //Then
    assertEquals(original, appended, "The original result should be returned");
    assertNull(appended.getNext(), "The original result should not have a next result");
  }

  @Test
  void GivenProducingResultWrapper_WhenAppendNoProducingResult_ThenAppendItInNonQuietMode() {
    //Given
    boolean quietMode = false;
    ResultWrapper original = new ResultWrapper(null, SqlCommandType.SELECT, quietMode);
    ResultWrapper toAppend = new ResultWrapper(null, SqlCommandType.SET, quietMode);
    //When
    ResultWrapper appended = original.append(toAppend);
    //Then
    assertEquals(original, appended, "The original result should not be returned");
    assertNotNull(appended.getNext(), "The original result should have a next result");
    assertEquals(toAppend, appended.getNext(), "The appended result should be the next result");
  }

  @Test
  void GivenNoProducingResultWrapper_WhenAppendProducingResult_ThenReturnAppendInQuietMode() {
    //Given
    boolean quietMode = true;
    ResultWrapper original = new ResultWrapper(null, SqlCommandType.BEGIN, quietMode);
    ResultWrapper toAppend = new ResultWrapper(null, SqlCommandType.SELECT, quietMode);
    //When
    ResultWrapper appended = original.append(toAppend);
    //Then
    assertEquals(toAppend, appended, "The appended result should be returned");
    assertNull(appended.getNext(), "The appended result should not have a next result");
  }

  @Test
  void GivenNoProducingResultWrapper_WhenAppendProducingResult_ThenAppendItInNonQuietMode() {
    //Given
    boolean quietMode = false;
    ResultWrapper original = new ResultWrapper(null, SqlCommandType.BEGIN, quietMode);
    ResultWrapper toAppend = new ResultWrapper(null, SqlCommandType.SELECT, quietMode);
    //When
    ResultWrapper appended = original.append(toAppend);
    //Then
    assertNotEquals(toAppend, appended, "The appended result should not be returned");
    assertNotNull(appended.getNext(), "The appended result should have a next result");
    assertEquals(toAppend, appended.getNext(), "The appended result should be the next result");
  }
}
