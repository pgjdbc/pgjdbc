package org.postgresql.hostchooser;

import java.sql.SQLException;
import java.util.Properties;

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
  public void registerFailure(String host, SQLException ex) {
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
  public long getConnectionTimeout(String host) {
    return 0;
  }

  @Override
  public boolean isInbuilt() {
    return true;
  }
}
