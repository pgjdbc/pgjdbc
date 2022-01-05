/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.io;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.jupiter.api.Assertions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BlobInputStreamFuzzyTest extends BaseTest4 {
  @Test
  public void test() throws Throwable {
    Random rnd = new Random();
    int size = rnd.nextInt(10000);
    byte[] data = new byte[size];
    rnd.nextBytes(data);
    System.out.println("size = " + size);

    byte[] expectedBuffer = new byte[size];
    byte[] actualBuffer = new byte[size];

    con.setAutoCommit(false);
    LargeObjectManager lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
    long oid = lom.createLO(LargeObjectManager.READWRITE);
    List<InputStreamAction> actions = new ArrayList<>();
    try {
      try (LargeObject blob = lom.open(oid)) {
        // Init test data
        blob.write(data);

        // Run multiple tests, so we explore several permutations of read, mark, reset calls
        for (int i = 0; i < 100; i++) {
          // Prepare the list of actions for the test
          // It is easier to just prepare the list in advance, and then work with it.
          // For instance, if the actions trigger failure, we can try removing a random one
          // to minimize the test case.
          actions.clear();
          generateActions(actions, size, 50, rnd);
          // TODO: test "buffer size" and "limit" parameters
          // Key idea: blob.getInputStream should return the same values as ByteArrayInputStream

          // TODO: should seek(0) be a part of blob.getInputStream?
          blob.seek(0);
          InputStream blobInputStream = blob.getInputStream();
          InputStream goldenInputStream = new ByteArrayInputStream(data);
          runActions(expectedBuffer, actualBuffer, actions, blobInputStream, goldenInputStream);
        }
      }
    } catch (Throwable t) {
      t.addSuppressed(new Throwable("buffer size=" + size + ", sequence of actions=\n" + IntStream.range(0, actions.size()).mapToObj(it -> it + " " + actions.get(it)).collect(Collectors.joining("\n"))) {
        @Override
        public synchronized Throwable fillInStackTrace() {
          // Avoid adding stacktrace, we just want to add sequence of actions that caused failure
          return this;
        }
      });
      throw t;
    } finally {
      lom.unlink(oid);
    }
  }

  private void generateActions(List<InputStreamAction> actions, int size, int numActions,
      Random rnd) {
    for (int i = 0; i < numActions; i++) {
      int nextActionIndex = rnd.nextInt(5);
      switch (nextActionIndex) {
        case 0: {
          actions.add(InputStreamAction.READ);
          break;
        }
        case 1: {
          int offset = rnd.nextInt(size);
          int length = rnd.nextInt(size - offset);
          actions.add(new InputStreamAction.ReadOffsetLength(offset, length));
          break;
        }
        case 2: {
          int skip = rnd.nextInt(size);
          actions.add(new InputStreamAction.Skip(skip));
          break;
        }
        case 3: {
          int readLimit = rnd.nextInt(size);
          actions.add(new InputStreamAction.Mark(readLimit));
          break;
        }
        case 4: {
          actions.add(InputStreamAction.RESET);
          break;
        }
      }
    }
  }

  private void runActions(byte[] expectedBuffer, byte[] actualBuffer,
      List<InputStreamAction> actions, InputStream blobInputStream,
      InputStream goldenInputStream) throws IOException {
    for (InputStreamAction action : actions) {
      if (action == InputStreamAction.READ) {
        int expected = goldenInputStream.read();
        int actual = blobInputStream.read();
        assertEquals(expected, actual, "read()");
      } else if (action instanceof InputStreamAction.ReadOffsetLength) {
        InputStreamAction.ReadOffsetLength act = (InputStreamAction.ReadOffsetLength) action;
        int offset = act.offset;
        int length = act.length;
        int expected = goldenInputStream.read(expectedBuffer, offset, length);
        int actual = blobInputStream.read(actualBuffer, offset, length);
        assertAll(
            () ->
                assertEquals(
                    expected,
                    actual,
                    () -> "return value of read(byte[], " + offset + ", " + length + ")"
                ),
            () -> {
              // Arrays.equals is Java 9+
              if (!ByteBuffer.wrap(expectedBuffer, offset, length).equals(ByteBuffer.wrap(actualBuffer, offset, length))) {
                Assertions.assertArrayEquals(Arrays.copyOfRange(expectedBuffer, offset,
                        offset + length), Arrays.copyOfRange(actualBuffer, offset, offset + length),
                    () -> "buffers from read(byte[], " + offset + ", " + length + ")");
              }
            }
        );
      } else if (action instanceof InputStreamAction.Skip) {
        int skip = ((InputStreamAction.Skip) action).skip;
        long expected = goldenInputStream.skip(skip);
        long actual = blobInputStream.skip(skip);
        assertEquals(expected, actual, () -> "skip(" + skip + ")");
      } else if (action instanceof InputStreamAction.Mark) {
        int readLimit = ((InputStreamAction.Mark) action).readLimit;
        goldenInputStream.mark(readLimit);
        blobInputStream.mark(readLimit);
      } else if (action == InputStreamAction.RESET) {
        goldenInputStream.reset();
        blobInputStream.reset();
      } else {
        throw new IllegalArgumentException("Unexpected action " + action);
      }
    }
  }
}
