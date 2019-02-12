/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

/**
 * An interface that extends {@link InsaneInterfaceHierachy the insane interface hierarchy},
 * useful to test the scalability and implementations of interface graph traversals.  A naive
 * recursive traversal of all interfaces and their parent/extended interfaces would take
 * 2 ^ {@link InsaneInterfaceHierachy#DEPTH} operations.
 */
public interface InsaneInterface extends InsaneInterfaceHierachy.TestHierarchy_16_1, InsaneInterfaceHierachy.TestHierarchy_16_2 {
  // No methods
}
