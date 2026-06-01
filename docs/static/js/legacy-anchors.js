// If the page carries a [data-legacy-anchors] script tag (emitted by
// the {{< legacy-anchors >}} shortcode on hub pages) AND the current
// URL has a hash that matches a known legacy anchor, redirect to the
// new URL+anchor.
//
// Hub pages exist because Hugo's `aliases:` mechanism drops URL
// fragments — without this script, a deep link like
//   /documentation/use/#sslmode
// would land on /documentation/reference/connection-properties/
// without scrolling to the right row.
const dataEl = document.querySelector('script[data-legacy-anchors]');
if (dataEl && window.location.hash) {
  let map;
  try {
    map = JSON.parse(dataEl.textContent);
  } catch (e) {
    map = null;
  }
  if (map) {
    const target = map[window.location.hash.toLowerCase()];
    if (target) {
      // replace() (not assign()) so the broken legacy URL doesn't end
      // up in the back-button history.
      window.location.replace(target);
    }
  }
}
