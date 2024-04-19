module org.postgresql.jdbc {
  requires static java.desktop;
  requires java.logging;
  requires java.management;
  requires java.naming;
  requires java.security.jgss;
  requires java.sql;
  requires java.transaction.xa;
  requires static org.checkerframework.checker.qual;
  requires static com.sun.jna;
  requires static com.sun.jna.platform;
  requires static waffle.jna;
  requires static org.osgi.service.jdbc;
  requires static osgi.core;

  exports org.postgresql;
  provides java.sql.Driver with org.postgresql.Driver;
}
