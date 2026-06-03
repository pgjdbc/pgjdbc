---
title: "Connection Fail-over"
---


To support simple connection fail-over it is possible to define multiple endpoints (host and port pairs) in the connection 
url separated by commas. The driver will try once to connect to each of them in order until the connection succeeds.
If none succeeds a normal connection exception is thrown.

The syntax for the connection url is: `jdbc:postgresql://host1:port1,host2:port2/database`

The simple connection fail-over is useful when running against a high availability postgres installation that has identical 
data on each node. For example streaming replication postgres or postgres-xc cluster.

For example an application can create two connection pools.
One data source is for writes, another for reads. The write pool limits connections only to a primary node:`jdbc:postgresql://node1,node2,node3/accounting?targetServerType=primary` .

And the read pool balances connections between secondary nodes, but allows connections also to a primary if no secondaries
are available: `jdbc:postgresql://node1,node2,node3/accounting?targetServerType=preferSecondary&loadBalanceHosts=true`

If a secondary fails, all secondaries in the list will be tried first. In the case that there are no available secondaries
the primary will be tried. If all the servers are marked as "can't connect" in the cache then an attempt
will be made to connect to all the hosts in the URL, in order.
