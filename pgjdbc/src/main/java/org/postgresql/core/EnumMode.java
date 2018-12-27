/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGProperty;


/**
 * @see PGProperty#ENUM_MODE
 * @see BaseConnection#getEnumMode()
 */
public enum EnumMode {
  NEVER,
  TYPEMAP,
  ALWAYS
}
