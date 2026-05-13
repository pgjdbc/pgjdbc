// Switch active tab inside <div data-code-tabs>...</div>.
// Buttons carry data-tab-target="<index>"; panels carry data-active="true|false".
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
});
