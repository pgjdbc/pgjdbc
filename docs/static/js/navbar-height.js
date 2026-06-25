// Measure the sticky .navbar and publish its rendered height as a CSS
// custom property on :root. Sticky elements below it (e.g. .param-table
// thead) read --navbar-height to park flush with its bottom edge.
//
// The static default in assets/sass/abstracts/_constants.scss is a sensible
// fallback if this script doesn't run (or runs late); the measured value
// overrides it once available.
const sync = () => {
  const navbar = document.querySelector('.navbar');
  if (!navbar) return;
  const h = navbar.getBoundingClientRect().height;
  document.documentElement.style.setProperty('--navbar-height', `${h}px`);
};

sync();

// Re-measure on viewport changes (font scaling, breakpoint hits, etc.).
const ro = ('ResizeObserver' in window) ? new ResizeObserver(sync) : null;
if (ro) {
  const navbar = document.querySelector('.navbar');
  if (navbar) ro.observe(navbar);
} else {
  window.addEventListener('resize', sync);
}
