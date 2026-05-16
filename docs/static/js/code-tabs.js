// Tab switching + clipboard-copy for <div data-code-tabs>...</div>.
// Buttons carry data-tab-target="<index>"; panels carry data-active="true|false".
// A single .code-tabs__copy button per container reads the currently-active
// panel's text and writes it to the clipboard.

document.querySelectorAll('[data-code-tabs]').forEach((root) => {
  const buttons = root.querySelectorAll('.code-tabs__btn');
  const panels  = root.querySelectorAll('.code-tabs__panel');

  buttons.forEach((btn) => {
    btn.addEventListener('click', () => {
      const idx = btn.getAttribute('data-tab-target');
      buttons.forEach((b) => b.setAttribute('aria-selected', b === btn ? 'true' : 'false'));
      panels.forEach((p) => p.setAttribute(
        'data-active',
        p.id.endsWith(`-panel-${idx}`) ? 'true' : 'false',
      ));
    });
  });

  // Optional copy button. Absent on tab blocks that opted out.
  const copyBtn = root.querySelector('.code-tabs__copy');
  if (!copyBtn) return;

  const label = copyBtn.querySelector('.code-tabs__copy-label') || copyBtn;
  const originalText = label.textContent;
  let resetTimer = null;

  copyBtn.addEventListener('click', async () => {
    const active = root.querySelector('.code-tabs__panel[data-active="true"]');
    if (!active) return;

    // Prefer the <code> element's text — that strips highlight-span wrappers
    // and gives just the snippet.
    const codeEl = active.querySelector('code');
    const text = (codeEl ? codeEl.innerText : active.innerText).trimEnd();
    if (!text) return;

    try {
      await navigator.clipboard.writeText(text);
      label.textContent = 'Copied';
      copyBtn.classList.add('is-copied');
    } catch (e) {
      label.textContent = 'Copy failed';
      copyBtn.classList.add('is-failed');
    }

    if (resetTimer) clearTimeout(resetTimer);
    resetTimer = setTimeout(() => {
      label.textContent = originalText;
      copyBtn.classList.remove('is-copied', 'is-failed');
    }, 1500);
  });
});
