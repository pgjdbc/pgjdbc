// Highlight search terms on the destination page after a click from the
// site search dropdown. Reads `?q=<term>` (set by lunr-search.js), splits
// the query into identifier-shaped tokens, then wraps every case-insensitive
// occurrence in <mark class="search-hit"> within the main content area.
//
// If the URL has no `#anchor`, scrolls the first hit into view so the user
// lands on context immediately.
(function () {
    const params = new URLSearchParams(window.location.search);
    const q = params.get("q");
    if (!q) return;

    // Same separator as lunr-search.js's identifier-aware tokenizer, so the
    // query splits the same way it indexed: "channel_binding" → [channel,
    // binding]. Keeps camelCase as one token; case-insensitive regex below
    // handles substring matches inside identifiers like `defaultRowFetchSize`.
    const tokens = q.split(/[\s\-_]+/).filter(function (t) { return t.length >= 2; });
    if (tokens.length === 0) return;

    const escape = function (s) { return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); };
    const pattern = "(" + tokens.map(escape).join("|") + ")";
    const re = new RegExp(pattern, "gi");

    // Scope the walk to article content. We skip the navbar (already
    // contains the user's query in its search input), the search dropdown
    // (rendered above an article, would re-mark its own children), code
    // blocks (sequence boundary is delicate, plus their styling already
    // foregrounds identifiers), and non-content elements that can't carry
    // text (script/style/noscript/template).
    const main = document.querySelector("main") || document.body;
    const SKIP_TAGS = new Set([
        "SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE", "INPUT", "TEXTAREA", "SELECT", "BUTTON",
    ]);
    const SKIP_SELECTORS = [
        ".navbar",
        ".searchResults",
        ".nav-mob",
        ".param-table__row-anchor",
    ];

    function shouldSkip(textNode) {
        let el = textNode.parentElement;
        while (el && el !== main) {
            if (SKIP_TAGS.has(el.tagName)) return true;
            if (el.classList && el.classList.contains("search-hit")) return true;
            for (const sel of SKIP_SELECTORS) {
                if (el.matches && el.matches(sel)) return true;
            }
            el = el.parentElement;
        }
        return false;
    }

    const walker = document.createTreeWalker(main, NodeFilter.SHOW_TEXT, {
        acceptNode: function (node) {
            if (!node.nodeValue || node.nodeValue.length < 2) return NodeFilter.FILTER_REJECT;
            if (shouldSkip(node)) return NodeFilter.FILTER_REJECT;
            return NodeFilter.FILTER_ACCEPT;
        },
    });

    // Collect first, mutate after — mutating during walk breaks the iterator.
    const nodes = [];
    let n;
    while ((n = walker.nextNode())) nodes.push(n);

    let firstHit = null;
    for (const node of nodes) {
        const text = node.nodeValue;
        re.lastIndex = 0;
        if (!re.test(text)) continue;
        re.lastIndex = 0;

        const frag = document.createDocumentFragment();
        let last = 0;
        let m;
        while ((m = re.exec(text)) !== null) {
            if (m.index > last) {
                frag.appendChild(document.createTextNode(text.slice(last, m.index)));
            }
            const mark = document.createElement("mark");
            mark.className = "search-hit";
            mark.textContent = m[0];
            frag.appendChild(mark);
            if (!firstHit) firstHit = mark;
            last = m.index + m[0].length;
            // Guard against zero-width matches looping forever.
            if (m[0].length === 0) re.lastIndex++;
        }
        if (last < text.length) {
            frag.appendChild(document.createTextNode(text.slice(last)));
        }
        node.parentNode.replaceChild(frag, node);
    }

    // If the URL pointed to a specific anchor, the browser already scrolled
    // there and we leave the viewport alone. Otherwise center the first hit.
    if (firstHit && !window.location.hash) {
        firstHit.scrollIntoView({ block: "center" });
    }
})();
