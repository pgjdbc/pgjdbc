// The script generates a random subset of valid jdk, os, timezone, and other axes.
// You can preview the results by running "node matrix.js"
// See https://github.com/vlsi/github-actions-random-matrix
let fs = require('fs');
let os = require('os');
const { RNG } = require('./rng');
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
    {value: 'corretto', vendor: 'amazon', weight: 1},
    {value: 'liberica', vendor: 'bellsoft', weight: 1},
    {value: 'microsoft', vendor: 'microsoft', weight: 1},
    {value: 'oracle', vendor: 'oracle', weight: 1},
    // There are issues running Semeru JDK with Gradle 8.5
    // See https://github.com/gradle/gradle/issues/27273
    // {value: 'semeru', vendor: 'ibm', weight: 4},
    {value: 'temurin', vendor: 'eclipse', weight: 1},
    {value: 'zulu', vendor: 'azul', weight: 1},
  ]
});

// We can't yet use EA here, see https://github.com/oracle-actions/setup-java/issues/65
const eaJava = '22';

// Below versions will be used for testing only
matrix.addAxis({
  name: 'java_version',
  title: x => 'Java ' + x,
  // Strings allow versions like 18-ea
  values: [
    '8',
    '11',
    '17',
    '21',
    eaJava,
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

function lessThan(minVersion) {
    return value => Number(value) < Number(minVersion);
}

matrix.setNamePattern([
    'java_version', 'java_distribution', 'pg_version', 'query_mode', 'scram', 'ssl', 'hash', 'os',
    'server_tz', 'tz', 'locale',
    'check_anorm_sbt', 'gss', 'replication', 'slow_tests',
]);

// We take EA builds from Oracle
matrix.imply({java_version: eaJava}, {java_distribution: {value: 'oracle'}})
matrix.exclude({ssl: {value: 'yes'}, pg_version: lessThan('9.3')});
// matrix.exclude(row => row.ssl.value === 'yes' && isLessThan(row.pg_version, '9.3'));
matrix.exclude({scram: {value: 'yes'}, pg_version: lessThan('10')});
matrix.exclude({replication: {value: 'yes'}, pg_version: lessThan('9.6')});
//org.postgresql.test.jdbc2.ArrayTest fails using simple mode for versions less than 9.0 with malformed Array literal
matrix.exclude({query_mode: {value: 'simple'}, pg_version: lessThan('9.1')});
//matrix.exclude({query_mode: {value: 'simple'}, pg_version: '8.4'});
// Microsoft ships Java 11+
matrix.imply({java_distribution: {value: 'microsoft'}}, {java_version: v => v >= 11});
// Oracle JDK is only supported for JDK 17 and later
matrix.imply({java_distribution: {value: 'oracle'}}, {java_version: v => v === eaJava || v >= 17});
// TODO: Semeru does not ship Java 21 builds yet
matrix.exclude({java_distribution: {value: 'semeru'}, java_version: '21'})
matrix.exclude({gss: {value: 'yes'}, os: ['windows-latest', 'macos-latest', 'self-hosted']})
if (process.env.GITHUB_REPOSITORY === 'pgjdbc/pgjdbc') {
    // PG images below 9.3 are x86_64 only
    matrix.exclude({os: 'self-hosted', pg_version: lessThan('9.3')});
}

// The most rare features should be generated the first
// For instance, we have a lot of PostgreSQL versions, so we generate the minimal the first
// It would have to generate other parameters, and it might happen it would cover "most recent Java" automatically
// Ensure at least one job with "same" hashcode exists
matrix.generateRow({hash: {value: 'same'}});
matrix.generateRow({scram: {value: 'yes'}});
// Ensure there's a row for Java EA. It is at the beginning to increase chances of covering cases like ssl=yes below
matrix.generateRow({java_version: eaJava});
// Ensure we have a job with the minimal and maximal PostgreSQL versions
matrix.generateRow({pg_version: matrix.axisByName.pg_version.values[0]});
matrix.generateRow({pg_version: matrix.axisByName.pg_version.values.slice(-1)[0]});
//Ensure at least one job with "simple" query_mode exists
matrix.generateRow({query_mode: {value: 'simple'}});
// Ensure there will be at least one job with minimal supported Java
matrix.generateRow({java_version: matrix.axisByName.java_version.values[0]});
// Ensure there will be at least one job with Java 17
matrix.generateRow({java_version: "17"});
// Ensure there will be at least one job with the latest Java (excluding EA)
matrix.generateRow({java_version: matrix.axisByName.java_version.values.slice(-2)[0]});
matrix.generateRow({ssl: {value: 'yes'}});
// Ensure at least one Windows and at least one Linux job is present (macOS is almost the same as Linux)
// matrix.generateRow({os: 'windows-latest'});
matrix.generateRow({os: 'ubuntu-latest'});
if (process.env.GITHUB_REPOSITORY === 'pgjdbc/pgjdbc') {
  matrix.generateRow({os: 'self-hosted'});
}
// Ensure we test all query_mode values
for (let query_mode of matrix.axisByName.query_mode.values) {
  matrix.generateRow({query_mode: query_mode});
}
for (let gss of matrix.axisByName.gss.values) {
  matrix.generateRow({gss: gss});
}
for (let xa of matrix.axisByName.xa.values) {
  matrix.generateRow({xa: xa});
}
for (let ssl of matrix.axisByName.ssl.values) {
  matrix.generateRow({ssl: ssl});
}
for (let replication of matrix.axisByName.replication.values) {
  matrix.generateRow({replication: replication});
}
const include = matrix.generateRows(process.env.MATRIX_JOBS || 5);
if (include.length === 0) {
  throw new Error('Matrix list is empty');
}
include.sort((a, b) => a.name.localeCompare(b.name, undefined, {numeric: true}));
include.forEach(v => {
    let gradleArgs = [
        `-Duser.country=${v.locale.country}`,
        `-Duser.language=${v.locale.language}`,
    ];
    v.extraGradleArgs = gradleArgs.join(' ');
});
include.forEach(v => {
  // Arguments passed to all the JVMs via _JAVA_OPTIONS
  let jvmArgs = [];
  // Extra JVM arguments passed to test execution
  let testJvmArgs = [];
  jvmArgs.push(`-Duser.country=${v.locale.country}`);
  jvmArgs.push(`-Duser.language=${v.locale.language}`);

  v.java_distribution = v.java_distribution.value;
  v.java_vendor = v.java_distribution.vendor;
  if (v.java_distribution === 'oracle') {
      v.oracle_java_website = v.java_version === eaJava ? 'jdk.java.net' : 'oracle.com';
  }
  v.non_ea_java_version = v.java_version === eaJava ? '' : v.java_version;
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
      includeTestTags.push('replication');
  }
  if (v.slow_tests === 'yes') {
      includeTestTags.push('org.postgresql.test.SlowTests');
  }
  if (v.xa === 'yes') {
      includeTestTags.push('xa');
  }

  v.includeTestTags = includeTestTags.join(' | ');

  if (v.gss === 'yes' || v.check_anorm_sbt === 'yes') {
      v.deploy_to_maven_local = true
  }
  if (v.hash.value === 'same') {
    // "same hashcode" causes issue for javac, and kotlinc,
    // so we pass it to test execution only
    // See https://github.com/pgjdbc/pgjdbc/pull/2821#issuecomment-1436013284
    testJvmArgs.push('-XX:+UnlockExperimentalVMOptions', '-XX:hashCode=2');
  }
  // Gradle does not work in tr_TR locale, so pass locale to test only: https://github.com/gradle/gradle/issues/17361
  jvmArgs.push(`-Duser.country=${v.locale.country}`);
  jvmArgs.push(`-Duser.language=${v.locale.language}`);
  let jit = v.java_distribution === 'semeru' ? 'open9j' : 'hotspot';
  if (jit === 'hotspot' && RNG.random() > 0.5) {
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
  // Force using /dev/urandom so we do not worry about draining entropy pool
  testJvmArgs.push('-Djava.security.egd=file:/dev/./urandom')
  v.extraJvmArgs = jvmArgs.join(' ');
  v.testExtraJvmArgs = testJvmArgs.join(' ::: ');
  delete v.hash;
});

console.log(include);

let filePath = process.env['GITHUB_OUTPUT'] || '';
if (filePath) {
    fs.appendFileSync(filePath, `matrix<<MATRIX_BODY${os.EOL}${JSON.stringify({include})}${os.EOL}MATRIX_BODY${os.EOL}`, {
        encoding: 'utf8'
    });
}
