/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.HashMap;

public class GettableHashMap<@NonNull K, @NonNull V>
    extends HashMap<K,V>
    implements Gettable<K,V> {

}
