/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import java.util.Properties;

/**
 * This class contains empty implementations of the HostChooser interface methods.
 *
 */
public abstract class AbstractHostChooser implements HostChooser {

  @Override
  public void init(String url, Properties info, HostRequirement targetServerType) {
    // for inbuilt HostChoosers do nothing
  }

  @Override
  public void registerSuccess(String host) {
    // for inbuilt HostChoosers do nothing
  }

  @Override
  public void registerFailure(String host, Exception ex) {
    // for inbuilt HostChoosers do nothing
  }

  @Override
  public void registerDisconnect(String host) {
    // for inbuilt HostChoosers do nothing
  }

  @Override
  public boolean isHostDrainingConnections(String host) {
    return false;
  }

  @Override
  public boolean isInbuilt() {
    return true;
  }
}
