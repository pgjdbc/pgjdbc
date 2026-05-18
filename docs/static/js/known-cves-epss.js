// Fills in EPSS (Exploit Prediction Scoring System) scores on the
// "known security advisories" banner that the changelogs/single layout
// renders. The layout emits one placeholder per CVE:
//
//   <span class="known-cves__epss" data-epss-cve="CVE-2024-1597">…</span>
//
// At page load we collect every CVE id on the page, batch-fetch them from
// FIRST.org (https://api.first.org/data/v1/epss?cve=A,B,C — public, CORS
// open, 24h Cache-Control), and replace each placeholder with the score
// and percentile. If the request fails or returns no row for an id, the
// placeholder is hidden — the banner still renders with severity / CVSS /
// fixed-in unaffected.
//
// EPSS deliberately is NOT cached at site-gen time: scores update daily
// from FIRST, and a stale build would surface yesterday's exploitation
// signal. The browser cache (24h, public) absorbs most repeat loads.

const API_BASE = 'https://api.first.org/data/v1/epss';

// Cap batch size; the API documents support for ~100, we never hit close
// to that on one page (max ~6 advisories per release).
const MAX_BATCH = 50;

function collectPlaceholders() {
  return Array.from(document.querySelectorAll('[data-epss-cve]'));
}

// Only CVE-prefixed ids have EPSS rows; advisories that only carry a
// GHSA-id (no CVE assigned) cannot be queried. Filter those out and
// hide their placeholders directly.
function filterQueryableCves(placeholders) {
  const queryable = [];
  for (const el of placeholders) {
    const id = el.dataset.epssCve;
    if (id && id.startsWith('CVE-')) {
      queryable.push(id);
    } else {
      el.hidden = true;
    }
  }
  return queryable;
}

async function fetchEpss(cveIds) {
  if (cveIds.length === 0) return new Map();
  const params = new URLSearchParams({ cve: cveIds.slice(0, MAX_BATCH).join(',') });
  const resp = await fetch(`${API_BASE}?${params}`, { credentials: 'omit' });
  if (!resp.ok) throw new Error(`FIRST.org EPSS API returned ${resp.status}`);
  const body = await resp.json();
  const map = new Map();
  for (const row of body.data || []) {
    map.set(row.cve, { epss: parseFloat(row.epss), percentile: parseFloat(row.percentile) });
  }
  return map;
}

// EPSS score is a probability (0..1); we render it as a percentage so
// distinctions in the typical "very low" range (0.001 vs 0.005) don't
// both collapse to "0.00". 1 decimal for sub-10% values, whole-number
// thereafter; values below 0.1% display as "<0.1%" since further
// precision is meaningless here.
//
// Percentile (also 0..1) is the rank of this CVE among all scored CVEs
// — a 65th-percentile CVE has higher exploitation likelihood than 65%
// of all CVEs in the corpus. Rendered as a whole-number percentage.
//
// The static `title=` attribute set in the Hugo partial explains the
// metric to hover users; we don't overwrite it here.
function formatEpssPercent(score) {
  const pct = score * 100;
  if (pct < 0.1) return '<0.1%';
  if (pct < 10) return pct.toFixed(1) + '%';
  return Math.round(pct) + '%';
}

function renderEpss(el, data) {
  if (!data) {
    el.hidden = true;
    return;
  }
  const score = formatEpssPercent(data.epss);
  const percentile = Math.round(data.percentile * 100);
  el.textContent = `EPSS ${score} (${percentile}th %ile)`;
}

async function fillInEpss() {
  const placeholders = collectPlaceholders();
  if (placeholders.length === 0) return;

  const cveIds = filterQueryableCves(placeholders);
  if (cveIds.length === 0) return;

  try {
    const scores = await fetchEpss(cveIds);
    for (const el of placeholders) {
      const id = el.dataset.epssCve;
      renderEpss(el, scores.get(id));
    }
  } catch (e) {
    // Network failure / CORS / FIRST.org down — degrade silently by
    // hiding placeholders. Console-log so it's debuggable but don't
    // surface anything visible to the reader.
    console.warn('EPSS fetch failed; hiding placeholders.', e);
    for (const el of placeholders) el.hidden = true;
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', fillInEpss);
} else {
  fillInEpss();
}
