// Updates the website data in docs/data for a new release.
//
// Usage: node docs/scripts/update-site.mjs [--check] <version> docs
//
// Run it in the pull request that prepares a release, after adding the
// changelog. The release deploys the website from the released commit as it
// is, so the data has to list the release before the release starts.
//
// With --check, the script changes no files. It fails if the changelog is
// missing or dated in the future, or if the data files do not list the
// release yet. The release workflow runs the check before it publishes
// anything, so the release stops before the jar reaches Maven Central.
//
// The script edits the TOML files block by block, as text, so comments and
// the layout of the files stay as they are. Running it twice for the same
// version changes the files only once.

import fs from 'node:fs';
import path from 'node:path';

const MONTHS = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

const args = process.argv.slice(2);
const checkOnly = args[0] === '--check';
if (checkOnly) {
  args.shift();
}
const [version, docsDir] = args;
if (!/^\d+\.\d+\.\d+$/.test(version ?? '') || !docsDir) {
  fail('usage: node docs/scripts/update-site.mjs [--check] <x.y.z> docs');
}

const changelog = findChangelog(version);
const outdated = [];
updateHomepageData(changelog);
updateVersions();
if (outdated.length) {
  fail(`${outdated.join(' and ')} do not list ${version} yet. Run`
    + ` "node docs/scripts/update-site.mjs ${version} docs" and commit`
    + ' the result before the release.');
}
if (checkOnly) {
  console.log(`The website data lists ${version}`);
}

function fail(message) {
  console.error(`update-site: ${message}`);
  process.exit(1);
}

// Compares x.y.z versions by number. Returns a negative number, zero, or a
// positive number, like a sort comparator.
function compareVersions(a, b) {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if (pa[i] !== pb[i]) {
      return pa[i] - pb[i];
    }
  }
  return 0;
}

// Finds docs/content/changelogs/<yyyy-mm-dd>-<version>-release.md. The home
// page template looks the changelog up by its version and shows its
// summary, so both must be in the front matter.
function findChangelog(version) {
  const dir = path.join(docsDir, 'content', 'changelogs');
  const escaped = version.replaceAll('.', '\\.');
  const pattern = new RegExp(
    `^(\\d{4})-(\\d{2})-(\\d{2})-${escaped}-release\\.md$`);
  const matches = fs.readdirSync(dir).filter((f) => pattern.test(f));
  if (matches.length !== 1) {
    fail(`expected one changelog named <yyyy-mm-dd>-${version}-release.md`
      + ` in ${dir}, found ${matches.length}`);
  }
  const file = matches[0];
  const text = fs.readFileSync(path.join(dir, file), 'utf8');
  const frontMatter = text.match(/^---\n([\s\S]*?)\n---/)?.[1] ?? '';
  if (!new RegExp(`^version:\\s*${escaped}\\s*$`, 'm').test(frontMatter)) {
    fail(`${file} must set "version: ${version}" in its front matter`);
  }
  if (!/^summary:\s*\S/m.test(frontMatter)) {
    fail(`${file} must set a summary in its front matter`);
  }
  // Hugo leaves out pages dated in the future. The changelog page would be
  // missing, and the home page build would fail because it cannot find it.
  // A pull request that prepares a release may run before the release day,
  // so only the check at release time looks at the date.
  const date = parseDate(frontMatter.match(/^date:\s*(.+?)\s*$/m)?.[1]);
  if (!date) {
    fail(`${file} must set a date such as 2026-07-06 10:18:00 -0400`);
  }
  if (checkOnly && date > new Date()) {
    fail(`${file} is dated ${date.toISOString()}, in the future;`
      + ' Hugo would not publish it');
  }
  const [, year, month, day] = file.match(pattern);
  return {
    file,
    date: `${Number(day)} ${MONTHS[Number(month) - 1]} ${year}`,
    url: `/changelogs/${file.replace(/\.md$/, '')}/`,
  };
}

// Parses a front matter date such as "2026-07-06 10:18:00 -0400". Some
// changelogs write a one-digit hour, as in "9:18:00". Without a time zone,
// Hugo reads the date as UTC, and so does this function. Returns undefined
// when the value does not look like a date.
function parseDate(value) {
  const m = value?.match(new RegExp(
    '^(\\d{4}-\\d{2}-\\d{2})'
    + '(?:[ T](\\d{1,2})(:\\d{2}(?::\\d{2})?))?'
    + '\\s*(Z|[+-]\\d{2}:?\\d{2})?$'));
  if (!m) {
    return undefined;
  }
  const [, day, hour = '0', rest = ':00', zone = 'Z'] = m;
  const offset = zone.replace(/^([+-]\d{2})(\d{2})$/, '$1:$2');
  return new Date(`${day}T${hour.padStart(2, '0')}${rest}${offset}`);
}

// Splits a TOML file into the text before the first table header and one
// chunk per table. A comment line directly above a header stays at the end
// of the chunk before it, so inserting a chunk before a header keeps that
// comment above the inserted table.
function splitChunks(text) {
  return text.split(/^(?=\[)/m);
}

function header(chunk) {
  return chunk.match(/^\[\[?([^\]]+)\]\]?/)?.[1];
}

function field(chunk, name) {
  return chunk.match(new RegExp(`^${name}\\s*=\\s*"([^"]*)"`, 'm'))?.[1];
}

function setField(chunk, name, value) {
  return chunk.replace(
    new RegExp(`^(${name}\\s*=\\s*)"[^"]*"`, 'm'),
    (_, prefix) => `${prefix}"${value}"`,
  );
}

// With --check, records a file that the update would change, and leaves the
// file as it is.
function updateFile(file, update) {
  const before = fs.readFileSync(file, 'utf8');
  const after = update(before);
  if (after === before) {
    console.log(`${file} already lists ${version}`);
  } else if (checkOnly) {
    outdated.push(file);
  } else {
    fs.writeFileSync(file, after);
    console.log(`Updated ${file}`);
  }
}

// Adds the release to the top of the "Latest Releases" list on the home
// page. If the release is newer than [current], it also becomes the current
// release, and the blurb goes away so the changelog summary shows instead.
function updateHomepageData(changelog) {
  updateFile(path.join(docsDir, 'data', 'homepagedata.toml'), (text) => {
    const chunks = splitChunks(text);

    const current = chunks.findIndex((c) => header(c) === 'current');
    if (current < 0) {
      fail('homepagedata.toml has no [current] table');
    }
    if (compareVersions(version, field(chunks[current], 'version')) > 0) {
      chunks[current] = setField(chunks[current], 'version', version)
        .replace(/^blurb\s*=.*\n/m, '');
    }

    const infos = chunks.flatMap((c, i) => (header(c) === 'info' ? [i] : []));
    if (!infos.some((i) => field(chunks[i], 'version') === version)) {
      const entry = '[[info]]\n'
        + `date = "${changelog.date}"\n`
        + `url = "${changelog.url}"\n`
        + `version = "${version}"\n\n`;
      chunks.splice(infos.length ? infos[0] : chunks.length, 0, entry);
    }
    return chunks.join('');
  });
}

// Makes the release the "Java 8" download if it is newer than the one
// listed, and lists the release that it replaces under past versions. An
// older release, such as a fix on an older branch, goes into the past
// versions in version order.
function updateVersions() {
  updateFile(path.join(docsDir, 'data', 'versions.toml'), (text) => {
    const chunks = splitChunks(text);

    const recent = chunks.findIndex(
      (c) => header(c) === 'recent' && field(c, 'j_name') === 'Java 8');
    if (recent < 0) {
      fail('versions.toml has no [[recent]] table with j_name= "Java 8"');
    }
    const listed = field(chunks[recent], 'version');
    let past = version;
    if (compareVersions(version, listed) > 0) {
      chunks[recent] = setField(
        setField(chunks[recent], 'version', version),
        'url', `/download/postgresql-${version}.jar`);
      past = listed;
    } else if (version === listed) {
      return text;
    }

    const pasts = chunks.flatMap((c, i) => (header(c) === 'past' ? [i] : []));
    if (pasts.some((i) => field(chunks[i], 'version') === past)) {
      return chunks.join('');
    }
    const entry = '[[past]]\n'
      + 'j_name= "Java 8"\n'
      + `version= "${past}"\n`
      + 'suffix=""\n'
      + 'description= "If you are using Java 8 or newer then you should use '
      + 'the JDBC 4.2 version."\n'
      + `url= "/download/postgresql-${past}.jar"\n\n`;
    const before = pasts.find(
      (i) => compareVersions(field(chunks[i], 'version'), past) < 0);
    if (before === undefined) {
      const last = chunks.length - 1;
      if (!chunks[last].endsWith('\n\n')) {
        chunks[last] += '\n';
      }
      chunks.push(entry.replace(/\n\n$/, '\n'));
    } else {
      chunks.splice(before, 0, entry);
    }
    return chunks.join('');
  });
}
