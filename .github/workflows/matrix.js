// The script generates a random subset of valid jdk, os, timezone, and other axes.
// You can preview the results by running "node matrix.js"
// See https://github.com/vlsi/github-actions-random-matrix
let {MatrixBuilder} = require('./matrix_builder');
const matrix = new MatrixBuilder();

// Some of the filter conditions might become unsatisfiable, and by default
// the matrix would ignore that.
// For instance, SCRAM requires PostgreSQL 10+, so if you ask
// matrix.generateRow({pg_version: '9.0'}), then it won't generate a row
// That behaviour is useful for PR testing. For instance, if you want to test only SCRAM=yes
// cases, then just comment out "value: no" in SCRAM axis, and the matrix would yield the matching
// parameters.
// However, if you add new testing parameters, you might want un-comment the following line
// to notice if you accidentally introduce unsatisfiable conditions.
// matrix.failOnUnsatisfiableFilters(true);

matrix.addAxis({
  name: 'java_distribution',
  values: [
    'zulu',
    'temurin',
    'liberica',
    'microsoft',
  ]
});

// TODO: support different JITs (see https://github.com/actions/setup-java/issues/279)
matrix.addAxis({name: 'jit', title: '', values: ['hotspot']});

matrix.addAxis({
  name: 'java_version',
  title: x => 'Java ' + x,
  // Strings allow versions like 18-ea
  values: [
    '8',
    '11',
    '17',
  ]
});

matrix.addAxis({
  name: 'pg_version',
  title: x => 'PG ' + x,
  // Strings allow versions like 18-ea
  values: [
    '8.4',
    '9.0',
    '9.1',
    '9.2',
    '9.3',
    '9.4',
    '9.5',
    '9.6',
    '10',
    '11',
    '12',
    '13',
    '14',
  ]
});

matrix.addAxis({
  name: 'tz',
  title: x => 'client_tz ' + x,
  values: [
    'America/New_York',
    'Pacific/Chatham',
    'UTC'
  ]
});

matrix.addAxis({
  name: 'server_tz',
  title: x => 'server_tz ' + x,
  values: [
    'America/New_York',
    'Pacific/Chatham',
    'UTC'
  ]
});

matrix.addAxis({
  name: 'os',
  title: x => x.replace('-latest', ''),
  values: [
    'ubuntu-latest',
    // We use docker-compose for launching PostgreSQL
    // 'windows-latest',
    // 'macos-latest',
    ...(process.env.GITHUB_REPOSITORY === 'pgjdbc/pgjdbc' ? ['self-hosted'] : [])
  ]
});

// Test cases when Object#hashCode produces the same results
// It allows capturing cases when the code uses hashCode as a unique identifier
matrix.addAxis({
  name: 'hash',
  values: [
    {value: 'regular', title: '', weight: 42},
    {value: 'same', title: 'same hashcode', weight: 1}
  ]
});
matrix.addAxis({
  name: 'locale',
  title: x => x.language + '_' + x.country,
  values: [
    {language: 'de', country: 'DE'},
    {language: 'fr', country: 'FR'},
    {language: 'ru', country: 'RU'},
    {language: 'tr', country: 'TR'},
  ]
});

matrix.addAxis({
  name: 'query_mode',
  values: [
    {value: 'simple', title: 'simple query', weight: 2},
    {value: 'extendedForPrepared', title: 'extendedForPrepared', weight: 2},
    {value: 'extended', title: '', weight: 10},
    {value: 'extendedCacheEverything', title: 'extendedCacheEverything', weight: 2}
  ]
});

matrix.addAxis({
  name: 'replication',
  title: x => (x.value === 'yes' ? '' : 'no_') + 'replication',
  values: [
    {value: 'yes', weight: 10},
// Replication requires PG 9.6+
    {value: 'no', weight: 10},
  ]
});

matrix.addAxis({
  name: 'slow_tests',
  title: x => (x.value === 'yes' ? '' : 'no_') + 'slow_tests',
  values: [
    {value: 'yes', weight: 1},
    {value: 'no', weight: 10},
  ]
});

matrix.addAxis({
  name: 'scram',
  title: x => (x.value === 'yes' ? '' : 'no_') + 'scram',
  values: [
    {value: 'yes', weight: 10},
    {value: 'no', weight: 10},
  ]
});

matrix.addAxis({
  name: 'ssl',
  title: x => (x.value === 'yes' ? '' : 'no_') + 'ssl',
  values: [
    {value: 'yes', weight: 10},
    {value: 'no', weight: 10},
  ]
});

matrix.addAxis({
  name: 'gss',
  title: x => (x.value === 'yes' ? '' : 'no_') + 'gss',
  values: [
    {value: 'yes', weight: 2},
    {value: 'no', weight: 10},
  ]
});

matrix.addAxis({
  name: 'xa',
  title: x => (x.value === 'yes' ? '' : 'no_') + 'xa',
  values: [
    {value: 'yes', weight: 10},
    {value: 'no', weight: 10},
  ]
});

matrix.addAxis({
  name: 'check_anorm_sbt',
  values: [
    // See https://github.com/pgjdbc/pgjdbc/issues/2537
    // {value: 'yes', title: 'check_anorm_sbt', weight: 30},
    {value: 'no', title: '', weight: 10},
  ]
});

function isLessThan(pg_version, minVersion){
    return Number(pg_version) < Number(minVersion);
}

matrix.setNamePattern([
    'java_version', 'java_distribution', 'pg_version', 'query_mode', 'scram', 'ssl', 'hash', 'os',
    'server_tz', 'tz', 'locale',
    'check_anorm_sbt', 'gss', 'replication', 'slow_tests',
]);

matrix.exclude(row => row.ssl.value === 'yes' && isLessThan(row.pg_version, '9.3'));
matrix.exclude(row => row.scram.value === 'yes' && isLessThan(row.pg_version, '10'));
matrix.exclude(row => row.replication.value === 'yes' && isLessThan(row.pg_version, '9.6'));

// Microsoft Java has no distribution for 8
matrix.exclude({java_distribution: 'microsoft', java_version: '8'});
matrix.exclude({gss: {value: 'yes'}, os: ['windows-latest', 'macos-latest', 'self-hosted']})
if (process.env.GITHUB_REPOSITORY === 'pgjdbc/pgjdbc') {
    // PG images below 9.3 are x86_64 only
    matrix.exclude(row => row.os === 'self-hosted' && isLessThan(row.pg_version, '9.3'));
}

// The most rare features should be generated the first
// For instance, we have a lot of PostgreSQL versions, so we generate the minimal the first
// It would have to generate other parameters, and it might happen it would cover "most recent Java" automatically
// Ensure at least one job with "same" hashcode exists
matrix.generateRow({hash: {value: 'same'}});
matrix.generateRow({scram: {value: 'yes'}});
// Ensure we have a job with the minimal and maximal PostgreSQL versions
matrix.generateRow({pg_version: matrix.axisByName.pg_version.values[0]});
matrix.generateRow({pg_version: matrix.axisByName.pg_version.values.slice(-1)[0]});
//Ensure at least one job with "simple" query_mode exists
matrix.generateRow({query_mode: {value: 'simple'}});
// Ensure there will be at least one job with minimal supported Java
matrix.generateRow({java_version: matrix.axisByName.java_version.values[0]});
// Ensure there will be at least one job with the latest Java
matrix.generateRow({java_version: matrix.axisByName.java_version.values.slice(-1)[0]});
matrix.generateRow({ssl: {value: 'yes'}});
// Ensure at least one Windows and at least one Linux job is present (macOS is almost the same as Linux)
// matrix.generateRow({os: 'windows-latest'});
matrix.generateRow({os: 'ubuntu-latest'});
if (process.env.GITHUB_REPOSITORY === 'pgjdbc/pgjdbc') {
  matrix.generateRow({os: 'self-hosted'});
}
const include = matrix.generateRows(process.env.MATRIX_JOBS || 5);
if (include.length === 0) {
  throw new Error('Matrix list is empty');
}
include.sort((a, b) => a.name.localeCompare(b.name, undefined, {numeric: true}));
include.forEach(v => {
  let jvmArgs = [];
  v.replication = v.replication.value;
  v.slow_tests = v.slow_tests.value;
  v.xa = v.xa.value;
  v.gss = v.gss.value;
  v.ssl = v.ssl.value;
  v.scram = v.scram.value;
  v.check_anorm_sbt = v.check_anorm_sbt.value;
  v.query_mode = v.query_mode.value;

  let includeTestTags = [];
  // See https://junit.org/junit5/docs/current/user-guide/#running-tests-tag-expressions
  includeTestTags.push('none()'); // untagged tests

  if (v.replication === 'yes') {
      includeTestTags.push('org.postgresql.test.Replication');
  }
  if (v.slow_tests === 'yes') {
      includeTestTags.push('org.postgresql.test.SlowTests');
  }
  if (v.xa === 'yes') {
      includeTestTags.push('org.postgresql.test.XaTests');
  }

  v.includeTestTags = includeTestTags.join(' | ');

  if (v.gss === 'yes' || v.check_anorm_sbt === 'yes') {
      v.deploy_to_maven_local = true
  }
  if (v.hash.value === 'same') {
    jvmArgs.push('-XX:+UnlockExperimentalVMOptions', '-XX:hashCode=2');
  }
  // Gradle does not work in tr_TR locale, so pass locale to test only: https://github.com/gradle/gradle/issues/17361
  jvmArgs.push(`-Duser.country=${v.locale.country}`);
  jvmArgs.push(`-Duser.language=${v.locale.language}`);
  if (v.jit === 'hotspot' && Math.random() > 0.5) {
    // The following options randomize instruction selection in JIT compiler
    // so it might reveal missing synchronization in TestNG code
    v.name += ', stress JIT';
    jvmArgs.push('-XX:+UnlockDiagnosticVMOptions');
    if (v.java_version >= 8) {
      // Randomize instruction scheduling in GCM
      // share/opto/c2_globals.hpp
      jvmArgs.push('-XX:+StressGCM');
      // Randomize instruction scheduling in LCM
      // share/opto/c2_globals.hpp
      jvmArgs.push('-XX:+StressLCM');
    }
    if (v.java_version >= 16) {
      // Randomize worklist traversal in IGVN
      // share/opto/c2_globals.hpp
      jvmArgs.push('-XX:+StressIGVN');
    }
    if (v.java_version >= 17) {
      // Randomize worklist traversal in CCP
      // share/opto/c2_globals.hpp
      jvmArgs.push('-XX:+StressCCP');
    }
  }
  v.testExtraJvmArgs = jvmArgs.join(' ');
  delete v.hash;
});

console.log(include);
console.log('::set-output name=matrix::' + JSON.stringify({include}));
