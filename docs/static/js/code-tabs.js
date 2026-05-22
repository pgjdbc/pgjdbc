// Tab switching + clipboard-copy for <div data-code-tabs>...</div>.
// Buttons carry data-tab-target="<index>"; panels carry data-active="true|false".
// A single .code-tabs__copy button per container reads the currently-active
// panel's text and writes it to the clipboard.
//
// Copy uses navigator.clipboard.writeText() where available (secure
// context: https://, localhost, 127.0.0.1) and falls back to a hidden
// <textarea> + document.execCommand('copy') for non-secure contexts
// (the dev server bound to a LAN IP, file://). The legacy path is
// deprecated but still works in every current browser.

async function copyText(text) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      // fall through to legacy path
    }
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.top = '-9999px';
  ta.style.left = '-9999px';
  document.body.appendChild(ta);
  ta.select();
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch (e) {
    ok = false;
  }
  document.body.removeChild(ta);
  return ok;
}

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

    // textContent is preferred over innerText: it ignores CSS visibility
    // and returns the source text verbatim. Chroma's per-line span
    // wrappers (<span class="line"><span class="cl">...</span>\n</span>)
    // keep the original newlines as text nodes, so textContent reads
    // back the same snippet the user sees.
    const codeEl = active.querySelector('code');
    const text = (codeEl ? codeEl.textContent : active.textContent || '').replace(/\s+$/, '');
    if (!text) {
      console.warn('code-tabs: empty snippet, nothing to copy');
      return;
    }

    const ok = await copyText(text);
    label.textContent = ok ? 'Copied' : 'Copy failed';
    copyBtn.classList.add(ok ? 'is-copied' : 'is-failed');

    if (resetTimer) clearTimeout(resetTimer);
    resetTimer = setTimeout(() => {
      label.textContent = originalText;
      copyBtn.classList.remove('is-copied', 'is-failed');
    }, 1500);
  });
});
