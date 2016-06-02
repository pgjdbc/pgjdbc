package org.postgresql;

/**
 * Created by davec on 2016-05-20.
 */
public interface PGNotificationListener {
  public void notification(PGNotification notification);
}
