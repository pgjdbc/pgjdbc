// In-page filter for {{< param-table >}}.
// Each table is wrapped in <div data-param-table> with an optional search
// input (data-param-table-search) and a count badge (data-param-table-count).
// Rows carry data-param-haystack containing the lower-cased
// "<name> <description>" string to match against.
document.querySelectorAll('[data-param-table]').forEach((root) => {
  const input = root.querySelector('[data-param-table-search]');
  if (!input) return;

  const rows  = Array.from(root.querySelectorAll('[data-param-row]'));
  const count = root.querySelector('[data-param-table-count]');
  const total = rows.length;

  const apply = () => {
    const q = input.value.trim().toLowerCase();
    let shown = 0;
    rows.forEach((row) => {
      const hay = row.getAttribute('data-param-haystack') || '';
      const match = !q || hay.includes(q);
      row.hidden = !match;
      if (match) shown += 1;
    });
    if (count) count.textContent = `${shown} of ${total}`;
  };

  input.addEventListener('input', apply);

  // Deep-link support: visiting #prop-foo should clear the filter
  // so the target row is visible before the browser scrolls to it.
  if (window.location.hash.startsWith('#prop-')) {
    input.value = '';
    apply();
  }
});
