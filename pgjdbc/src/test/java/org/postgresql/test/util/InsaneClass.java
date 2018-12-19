/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

/**
 * A class that implements {@link InsaneInterfaceHierachy the insane interface hierarchy},
 * useful to test the scalability and implementations of interface graph traversals.  A naive
 * recursive traversal of all interfaces and their parent/extended interfaces would take
 * 2 ^ {@link InsaneInterfaceHierachy#DEPTH} operations.
 */
public class InsaneClass implements InsaneInterface {
  // No methods
}
