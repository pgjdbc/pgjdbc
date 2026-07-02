// The script generates a random subset of valid jdk, os, timezone, and other axes.
// You can preview the results by running "node matrix.mjs"
// See https://github.com/vlsi/github-actions-random-matrix
import { appendFileSync } from 'fs';
import { EOL } from 'os';
import { createGitHubMatrixBuilder } from '@vlsi/github-actions-random-matrix/github';
const { matrix, random } = createGitHubMatrixBuilder();

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
const eaJava = '26';

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
    '25',
    eaJava,
  ]
});

// The newest stable PostgreSQL major. It generates the pg_version axis (10..MAX_PG)
// and pins the server of the coverage job below. Renovate bumps it when a new
// `postgres:<major>` image reaches Docker Hub (see the custom manager in renovate.json),
// which is also when we can start testing that release. A string so it compares equal to
// the axis values, which are strings.
// renovate: datasource=docker depName=postgres versioning=regex:^(?<major>\d+)$
const MAX_PG = '18';

matrix.addAxis({
  name: 'pg_version',
  title: x => 'PG ' + x,
  // Strings allow versions like 18-ea.
  // PostgreSQL before 10 used an x.y major scheme, so those are listed by hand.
  // From 10 on the major is a single number, so 10..MAX_PG is generated and adding
  // a new major is a one-line Renovate bump of MAX_PG above.
  values: [
    '9.1',
    '9.2',
    '9.3',
    '9.4',
    '9.5',
    '9.6',
    // 10, 11, …, MAX_PG
    ...Array.from({length: Number(MAX_PG) - 10 + 1}, (_, i) => String(10 + i)),
  ]
});

// Test with PostgreSQL HEAD for branch-based builds only.
if ((process.env.GITHUB_REF || '').startsWith('refs/heads/')) {
  matrix.axisByName.pg_version.values.push('HEAD');
}

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
  title: x => (x.value || x).replace('-latest', ''),
  values: [
    {value: 'ubuntu-latest', weight: 4},
    {value: 'windows-latest', weight: 1},
    // TODO: uncomment once the test failures are fixed
    // {value: 'macos-latest', weight: 1},
  ]
});

// Test cases when Object#hashCode produces the same results
// It allows capturing cases when the code uses hashCode as a unique identifier
matrix.addAxis({
  name: 'hash',
  values: [
    {value: 'regular', title: '', weight: 10},
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
    {value: 'no', weight: 5},
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
  name: 'oauth',
  title: x => (x.value === 'yes' ? '' : 'no_') + 'oauth',
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
  name: 'autosave',
  title: x => x.value === 'never' ? '' : 'autosave ' + x.value,
  values: [
    {value: 'always', weight: 5},
    {value: 'never', weight: 30},
    {value: 'conservative', weight: 5},
  ]
});

matrix.addAxis({
  name: 'cleanupSavepoints',
  title: x => x.value === 'true' ? 'cleanupSavepoints' : '',
  values: [
    {value: 'true', weight: 5},
    {value: 'false', weight: 5},
  ]
});

matrix.addAxis({
  name: 'adaptive_fetch',
  title: x => x.value === 'yes' ? 'adaptive_fetch' : '',
  values: [
      {value: 'yes', weight: 30},
      {value: 'no', weight: 70}, // This is the default value, prefer testing it more
  ]
});

matrix.addAxis({
  name: 'rewrite_batch_inserts',
  title: x => x.value === 'yes' ? 'rewrite_batch_inserts' : '',
  values: [
      {value: 'yes', weight: 50}, // This is a non-default value; however, it is often used
      {value: 'no', weight: 50},
  ]
});

matrix.addAxis({
  name: 'query_timeout',
  title: x => x == '' ? '' : 'query_timeout ' + x,
  values: [
    '',
    '15'
  ]
});

// Occasionally constrain the JVM to a single CPU via -XX:ActiveProcessorCount=1. This shrinks
// JVM-internal pools (ForkJoinPool common pool, GC threads, etc.) and has caught regressions
// where bursty work cannot keep up with producers — e.g. LazyCleaner backed by FJP, see
// https://github.com/pgjdbc/pgjdbc/issues/4037
// Both HotSpot and OpenJ9 support the flag.
matrix.addAxis({
  name: 'cpu_count',
  title: x => x.value === '1' ? 'ActiveProcessorCount=1' : '',
  values: [
    {value: '1', weight: 1},
    {value: 'default', weight: 10},
  ]
});

matrix.addAxis({
  name: 'assertions',
  title: x => x.value === 'yes' ? 'assertions' : '',
  values: [
    {value: 'yes', weight: 3},
    {value: 'no', weight: 10},
  ]
});

function lessThan(minVersion) {
    return value => Number(value) < Number(minVersion);
}

matrix.setNamePattern([
    'java_version', 'java_distribution', 'pg_version', 'query_mode', 'scram', 'ssl', 'hash', 'os',
    'server_tz', 'tz', 'locale',
    'gss', 'oauth', 'replication', 'slow_tests',
    'adaptive_fetch', 'rewrite_batch_inserts', 'query_timeout',
    'autosave', 'cleanupSavepoints', 'cpu_count', 'assertions'
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
// Oracle JDK is only supported for JDK 21 and later
matrix.imply({java_distribution: {value: 'oracle'}}, {java_version: v => v === eaJava || v >= 21});
// TODO: Semeru does not ship Java 21 builds yet
matrix.exclude({java_distribution: {value: 'semeru'}, java_version: '21'})
matrix.imply({gss: {value: 'yes'}}, {os: {value: 'ubuntu-latest'}})
// The oauth auth method requires PostgreSQL 18, and the test relies on the
// Keycloak container plus the pg_oidc_validator deb, both of which only exist
// for PG 18 on the Docker-based (Linux) setup.
matrix.imply({oauth: {value: 'yes'}}, {pg_version: '18'})
matrix.imply({oauth: {value: 'yes'}}, {os: {value: 'ubuntu-latest'}})
// ikalnytskyi/action-setup-postgres supports PostgreSQL 14+ only
matrix.exclude({os: {value: ['windows-latest', 'macos-latest']}, pg_version: lessThan('14')});
// HEAD is built from pgdg-snapshot inside Docker, which only runs on Linux.
matrix.imply({pg_version: 'HEAD'}, {os: {value: 'ubuntu-latest'}});
// cleanupSavepoints is not relevant when autosave=never
matrix.imply({autosave: {value: 'never'}}, {cleanupSavepoints: {value: 'false'}});

// Collect coverage from a single job that turns on every feature that moves coverage (ssl,
// scram, xa, replication, the latest stable server, one query mode). The other flags add at
// most a line or two to line/branch coverage, so leaving them to the random fill keeps the
// corpus stable between builds without a fixed seed.
// Latest non-EA Java from the axis, so this need not be bumped when Java versions change.
const LATEST_JAVA = matrix.axisByName.java_version.values.filter(v => v !== eaJava).slice(-1)[0];

// Drive the whole matrix from one batch of requirements. generateRows guarantees a row for
// each entry, packs them into as few jobs as it can, and spends the rest of the MATRIX_JOBS
// budget on pairwise coverage. Unlike a sequence of generateRow() calls, the job count is
// exactly MATRIX_JOBS and the result no longer depends on the order of the list, so the
// rarest-first ordering the imperative version relied on is gone. Requirements already met by
// another row (e.g. the coverage job pins scram=yes and the latest Java) cost no extra job.
const include = matrix.generateRows(Number(process.env.MATRIX_JOBS || 6), {
  require: [
    // Collect coverage on one pinned job. It is the most specific requirement, so the batch
    // packer anchors a row on it; tag() flags that row without a second pass over the result.
    {
      filter: {
        os: {value: 'ubuntu-latest'},        // coverage needs the Docker PostgreSQL (Linux only)
        pg_version: MAX_PG,
        java_version: LATEST_JAVA, java_distribution: {value: 'temurin'},
        query_mode: {value: 'extended'},
        ssl: {value: 'yes'}, scram: {value: 'yes'},
        xa: {value: 'yes'}, replication: {value: 'yes'},
        // slow tests add ~0 coverage but cost runtime; GSS exercises the driver's encryption
        // paths and the krb5 setup runs anyway on this Linux job, so keep it on.
        slow_tests: {value: 'no'}, gss: {value: 'yes'},
      },
      tag: row => { row.collectCoverage = true; },
    },
    // Ensure at least one job with "same" hashcode exists
    {hash: {value: 'same'}},
    {scram: {value: 'yes'}},
    // Ensure there's a row for Java EA
    {java_version: eaJava},
    // Ensure we have a job with the minimal and maximal PostgreSQL versions
    {pg_version: matrix.axisByName.pg_version.values[0]},
    {pg_version: matrix.axisByName.pg_version.values.slice(-1)[0]},
    // Ensure at least one job with "simple" query_mode exists
    {query_mode: {value: 'simple'}},
    // Ensure there will be at least one job with minimal supported Java
    {java_version: matrix.axisByName.java_version.values[0]},
    // Ensure there will be at least one job with Java 17
    {java_version: "17"},
    // Ensure there will be at least one job with the latest Java (excluding EA)
    {java_version: LATEST_JAVA},
    // Ensure OAuth is exercised in at least one job.
    {oauth: {value: 'yes'}},
    // Ensure at least one job with autosave=always
    {autosave: {value: 'always'}},
    // Ensure we test all values of the axes below
    ...matrix.allAxisValues('query_mode'),
    ...matrix.allAxisValues('gss'),
    ...matrix.allAxisValues('xa'),
    ...matrix.allAxisValues('ssl'),
    ...matrix.allAxisValues('replication'),
    ...matrix.allAxisValues('os'),
    ...matrix.allAxisValues('cpu_count'),
    ...matrix.allAxisValues('assertions'),
  ],
});
if (include.length === 0) {
  throw new Error('Matrix list is empty');
}
if (!include.some(v => v.collectCoverage)) {
  throw new Error('Could not generate the coverage job; check that the pinned axes are satisfiable');
}
include.sort((a, b) => a.name.localeCompare(b.name, undefined, {numeric: true}));
include.forEach(v => {
    let gradleArgs = [];
    if (v.collectCoverage) {
        // Build the aggregate report as part of the main test run. The workflow
        // re-runs it on its own if the tests fail (see .github/workflows/main.yml).
        gradleArgs.push('jacocoReport');
    }
    gradleArgs.push(
        `-Duser.country=${v.locale.country}`,
        `-Duser.language=${v.locale.language}`,
    );
    v.extraGradleArgs = gradleArgs.join(' ');
    if (v.collectCoverage) {
        // Pin a fixed value so the coverage corpus is stable.
        v.assumeMinServerVersion = '';
        v.name += ', coverage';
    } else {
        // 8.0 is here to test a case when somebody configured assumeMinServerVersion=8.0, and forgot to update it.
        // The idea is that everything should still work since the option is just a hint to the driver
        // on the minimal set of features it can use when connecting to the database.
        let assumeMinServerVersion = ['', '', '8.0', ...matrix.axisByName.pg_version.values.filter(x => Number(x) <= Number(v.pg_version))];
        v.assumeMinServerVersion = assumeMinServerVersion[Math.floor(random() * assumeMinServerVersion.length)];
    }
    if (v.assumeMinServerVersion !== '') {
        v.name += ', assume min version ' + v.assumeMinServerVersion;
    }
});
include.forEach(v => {
  // Arguments passed to all the JVMs via _JAVA_OPTIONS
  let jvmArgs = [];
  // Extra JVM arguments passed to test execution
  let testJvmArgs = [];
  jvmArgs.push(`-Duser.country=${v.locale.country}`);
  jvmArgs.push(`-Duser.language=${v.locale.language}`);

  v.os = v.os.value;
  const {value: javaDistribution, vendor: javaVendor} = v.java_distribution;
  v.java_distribution = javaDistribution;
  v.java_vendor = javaVendor;
  if (v.java_distribution === 'oracle') {
      v.oracle_java_website = v.java_version === eaJava ? 'jdk.java.net' : 'oracle.com';
  }
  v.non_ea_java_version = v.java_version === eaJava ? '' : v.java_version;
  v.replication = v.replication.value;
  v.slow_tests = v.slow_tests.value;
  v.xa = v.xa.value;
  v.gss = v.gss.value;
  v.oauth = v.oauth.value;
  v.ssl = v.ssl.value;
  v.scram = v.scram.value;
  v.query_mode = v.query_mode.value;
  v.adaptive_fetch = v.adaptive_fetch.value;
  v.rewrite_batch_inserts = v.rewrite_batch_inserts.value;
  v.autosave = v.autosave.value;
  v.cleanupSavepoints = v.cleanupSavepoints.value;

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
  if (jit === 'hotspot' && random() > 0.5) {
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
  if (v.assumeMinServerVersion) {
      testJvmArgs.push(`-DassumeMinServerVersion=${v.assumeMinServerVersion}`);
  }
  if (v.adaptive_fetch === 'yes') {
      testJvmArgs.push('-DadaptiveFetch=true');
  }
  if (v.rewrite_batch_inserts === 'yes') {
      testJvmArgs.push('-DreWriteBatchedInserts=true');
  }
  if (v.query_timeout) {
      testJvmArgs.push(`-DqueryTimeout=${v.query_timeout}`);
  }
  if (v.autosave !== 'never') {
      testJvmArgs.push(`-Dautosave=${v.autosave}`);
  }
  if (v.cleanupSavepoints === 'true') {
      testJvmArgs.push('-DcleanupSavepoints=true');
  }
  if (v.gss === 'no') {
      testJvmArgs.push('-DskipGssEncryption=true');
  }
  if (v.cpu_count.value === '1') {
      // Constrains ForkJoinPool common pool to a single worker, exposing FJP submit/compensation
      // overhead in code paths like LazyCleaner. See https://github.com/pgjdbc/pgjdbc/issues/4037
      // Both HotSpot and OpenJ9 support -XX:ActiveProcessorCount.
      testJvmArgs.push('-XX:ActiveProcessorCount=1');
  }
  delete v.cpu_count;
  if (v.assertions.value === 'yes') {
      testJvmArgs.push('-ea');
  }
  delete v.assertions;
  if (v.oauth === 'yes') {
      jvmArgs.push('-Denable_oauth_tests=true');
  }
  v.extraJvmArgs = jvmArgs.join(' ');
  v.testExtraJvmArgs = testJvmArgs.join(' ::: ');
  delete v.hash;
});

if (process.argv.includes('--coverage')) {
  const coverage = matrix.pairCoverageReport();
  console.log(`Pair coverage: ${coverage.covered}/${coverage.total} (${coverage.percentage}%), weight coverage ${coverage.weightPercentage}%`);
} else {
  console.log(include);
  let filePath = process.env['GITHUB_OUTPUT'] || '';
  if (filePath) {
    appendFileSync(filePath, `matrix<<MATRIX_BODY${EOL}${JSON.stringify({include})}${EOL}MATRIX_BODY${EOL}`, {
      encoding: 'utf8'
    });
  }
}
