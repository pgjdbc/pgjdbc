/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import static java.util.Arrays.asList;
import static java.util.Collections.shuffle;
import static java.util.Collections.sort;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.GlobalHostStatusTracker.HostSpecStatus;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

/**
 * HostChooser that keeps track of known host statuses.
 */
public class MultiHostChooser implements HostChooser {
  private HostSpec[] hostSpecs;
  private final HostRequirement targetServerType;
  private int hostRecheckTime;
  private boolean loadBalance;

  protected MultiHostChooser(HostSpec[] hostSpecs, HostRequirement targetServerType,
      Properties info) {
    this.hostSpecs = hostSpecs;
    this.targetServerType = targetServerType;
    try {
      hostRecheckTime = PGProperty.HOST_RECHECK_SECONDS.getInt(info) * 1000;
      loadBalance = PGProperty.LOAD_BALANCE_HOSTS.getBoolean(info);
    } catch (PSQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Iterator<CandidateHost> iterator() {
    if (targetServerType == HostRequirement.preferSlave ) {
      // first return candidates for HostRequirement.slave and then for HostRequirement.any
      Iterator<CandidateHost> candidateHostIter = new Iterator<CandidateHost>() {

        private HostRequirement currentTargetServerType = HostRequirement.slave;
        private Iterator<HostSpec> iter = hostSpecIterator(currentTargetServerType);
        private boolean hasNextIter = true;

        @Override
        public boolean hasNext() {
          if (!iter.hasNext() && hasNextIter) {
            currentTargetServerType = HostRequirement.any;
            iter = hostSpecIterator(currentTargetServerType);
            hasNextIter = false;
          }

          return iter.hasNext();
        }

        @Override
        public CandidateHost next() {
          if (!iter.hasNext() && hasNextIter) {
            currentTargetServerType = HostRequirement.any;
            iter = hostSpecIterator(currentTargetServerType);
            hasNextIter = false;
          }

          return new SimpleCandidateHost(iter.next(), currentTargetServerType);
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException("remove");
        }
      };

      return candidateHostIter;
    } else {
      Iterator<CandidateHost> candidateHostIter = new Iterator<CandidateHost>() {

        private Iterator<HostSpec> iter = hostSpecIterator(targetServerType);

        @Override
        public boolean hasNext() {
          return iter.hasNext();
        }

        @Override
        public CandidateHost next() {
          return new SimpleCandidateHost(iter.next(), targetServerType);
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException("remove");
        }
      };

      return candidateHostIter;
    }
  }

  private Iterator<HostSpec> hostSpecIterator(HostRequirement targetServerType) {
    List<HostSpecStatus> candidates =
        GlobalHostStatusTracker.getCandidateHosts(hostSpecs, targetServerType, hostRecheckTime);
    // if no candidates are suitable (all wrong type or unavailable) then we try original list in
    // order
    if (candidates.isEmpty()) {
      return asList(hostSpecs).iterator();
    }
    if (candidates.size() == 1) {
      return asList(candidates.get(0).host).iterator();
    }
    if (!loadBalance) {
      sortCandidates(candidates);
    } else {
      shuffleCandidates(candidates);
    }
    return extractHostSpecs(candidates).iterator();
  }

  private void sortCandidates(List<HostSpecStatus> candidates) {
    sort(candidates, new HostSpecByTargetServerTypeComparator());
  }

  private void shuffleCandidates(List<HostSpecStatus> candidates) {
    shuffle(candidates);
  }

  private List<HostSpec> extractHostSpecs(List<HostSpecStatus> hostSpecStatuses) {
    List<HostSpec> hostSpecs = new ArrayList<HostSpec>(hostSpecStatuses.size());
    for (HostSpecStatus hostSpecStatus : hostSpecStatuses) {
      hostSpecs.add(hostSpecStatus.host);
    }
    return hostSpecs;
  }

  class HostSpecByTargetServerTypeComparator implements Comparator<HostSpecStatus> {
    @Override
    public int compare(HostSpecStatus o1, HostSpecStatus o2) {
      int r1 = rank(o1.status, targetServerType);
      int r2 = rank(o2.status, targetServerType);
      return r1 == r2 ? 0 : r1 > r2 ? -1 : 1;
    }

    private int rank(HostStatus status, HostRequirement targetServerType) {
      int rankVal = 0;
      switch (targetServerType) {
        case master:
          if (status == HostStatus.Master) {
            rankVal = 1;
          } else if (status == null || status == HostStatus.ConnectOK) {
            rankVal = 0;
          } else if (status == HostStatus.Slave) {
            rankVal = -1;
          } else { //HostStatus.ConnectFail
            rankVal = -2;
          }
          break;
        case slave:
          if (status == HostStatus.Slave) {
            rankVal = 1;
          } else if (status == null || status == HostStatus.ConnectOK) {
            rankVal = 0;
          } else if (status == HostStatus.Master) {
            rankVal = -1;
          } else { //HostStatus.ConnectFail
            rankVal = -2;
          }
          break;
        default: //any, and should never got preferSlave here
          if (status != HostStatus.ConnectFail) {
            rankVal = 0;
          } else {
            rankVal = -1;
          }
      }
      return rankVal;
    }
  }
}
