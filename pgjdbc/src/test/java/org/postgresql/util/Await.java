/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/* changes were made to move it into the org.postgresql.util package
 *
 * Copyright 2022 Juan Lopes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.postgresql.util;

import java.time.Duration;

public class Await {
  public static void until(String message, Duration timeout, Condition condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (!condition.get()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("Condition not met within " + timeout + ": " + message);
      }
      Thread.sleep(100);
    }
  }

  public interface Condition {
    boolean get();
  }
}
