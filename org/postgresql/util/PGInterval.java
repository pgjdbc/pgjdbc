/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.util;

import java.io.Serializable;

public class PGInterval extends PGobject implements Serializable, Cloneable
{
  public PGInterval()
  {
    setType("interval");
  }
  public PGInterval(String value )
  {
    this.value = value;
  }

  /*
   * This must be overidden to allow the object to be cloned
   */
  public Object clone()
  {
    return new PGInterval( value );
  }
}
