/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql;

/**
 * Created by davec on 2016-05-20.
 */
public interface PGNotificationListener {
  void notification(PGNotification notification);
}
