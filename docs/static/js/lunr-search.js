// This module is loaded via `<script defer type="module">` (see scripts.html),
// which by spec executes after the document is parsed — the DOM is ready,
// so no DOMContentLoaded gate is needed.
(function () {
    const MAX_RESULTS = 8;
    const FRAGMENT_RADIUS = 120;
    const MAX_FRAGMENTS = 2;
    const DEBOUNCE_MS = 120;

    const form = document.getElementById("search");
    const input = document.getElementById("search-input");
    const dropdown = document.getElementById("search-results-dropdown");
    const host = document.getElementById("host");
    if (!form || !input || !dropdown || !host) return;
    const base = host.href.replace(/\/$/, "");

    let index = null;
    let lookup = null;
    let indexPromise = null;
    let debounceTimer = null;
    let activeIndex = -1;
    let currentResults = [];

    // Open dropdown on focus when there's a query; trigger live search on input.
    input.addEventListener("input", function () {
        scheduleSearch();
    });
    input.addEventListener("focus", function () {
        if (input.value.trim()) scheduleSearch(0);
    });

    // Keyboard navigation within the dropdown.
    input.addEventListener("keydown", function (event) {
        if (event.key === "ArrowDown") {
            event.preventDefault();
            moveActive(1);
        } else if (event.key === "ArrowUp") {
            event.preventDefault();
            moveActive(-1);
        } else if (event.key === "Enter") {
            if (activeIndex >= 0 && currentResults[activeIndex]) {
                event.preventDefault();
                window.location.href = currentResults[activeIndex].uri;
            }
        } else if (event.key === "Escape") {
            if (!dropdown.hidden) {
                event.preventDefault();
                closeDropdown();
            } else {
                input.blur();
            }
        }
    });

    // Submit handler: only matters if the user presses Enter without a selection.
    // The first result is the safe default; if there is none, do nothing.
    form.addEventListener("submit", function (event) {
        event.preventDefault();
        if (currentResults[0]) window.location.href = currentResults[0].uri;
    });

    // Click outside closes the dropdown; click on a result follows the link.
    document.addEventListener("click", function (event) {
        if (!form.contains(event.target) && !dropdown.contains(event.target)) {
            closeDropdown();
        }
    });

    // Global `/` shortcut: focus the search input when not already typing in a field.
    document.addEventListener("keydown", function (event) {
        if (event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey) return;
        const t = event.target;
        if (t === input) return;
        const tag = t && t.tagName;
        const editable = t && (
            tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" ||
            (t.isContentEditable === true)
        );
        if (editable) return;
        event.preventDefault();
        input.focus();
        input.select();
    });

    function scheduleSearch(delay) {
        clearTimeout(debounceTimer);
        const ms = typeof delay === "number" ? delay : DEBOUNCE_MS;
        debounceTimer = setTimeout(runSearch, ms);
    }

    function runSearch() {
        const term = input.value.trim();
        if (!term) {
            closeDropdown();
            return;
        }
        ensureIndex().then(function () {
            renderResults(term, performSearch(term));
        }).catch(function () {
            renderError();
        });
    }

    // Drop-in replacement for lunr.tokenizer that splits each separator-delimited
    // chunk further into camelCase and PascalCase components, and emits adjacent
    // bigrams. The original (lowercased) chunk is always retained, so verbatim
    // queries like `defaultRowFetchSize` still hit. Snake_case is handled via
    // the broadened separator, which now includes underscores.
    //
    // All emitted sub-tokens share the parent chunk's `position` metadata, so
    // a hit on `executor` inside `QueryExecutorImpl` highlights the whole
    // identifier in fragments — that's the right granularity for code names.
    const IDENTIFIER_SEPARATOR = /[\s\-_]+/;

    function identifierAwareTokenizer(obj, metadata) {
        if (obj == null || obj == undefined) return [];
        if (Array.isArray(obj)) {
            return obj.reduce(function (acc, item) {
                return acc.concat(identifierAwareTokenizer(item, Object.assign({}, metadata)));
            }, []);
        }
        const str = String(obj);
        const tokens = [];
        let sliceStart = 0;
        for (let sliceEnd = 0; sliceEnd <= str.length; sliceEnd++) {
            const char = str.charAt(sliceEnd);
            const atEnd = sliceEnd === str.length;
            if (atEnd || IDENTIFIER_SEPARATOR.test(char)) {
                const sliceLength = sliceEnd - sliceStart;
                if (sliceLength > 0) {
                    expandIdentifier(
                        tokens,
                        str.slice(sliceStart, sliceEnd),
                        sliceStart,
                        sliceLength,
                        metadata
                    );
                }
                sliceStart = sliceEnd + 1;
            }
        }
        return tokens;
    }
    identifierAwareTokenizer.separator = IDENTIFIER_SEPARATOR;

    function expandIdentifier(tokens, text, sliceStart, sliceLength, baseMetadata) {
        const orig = text.toLowerCase();
        tokens.push(makeToken(orig, sliceStart, sliceLength, tokens.length, baseMetadata));
        // Plain lowercase text has nothing to split; skip the regex work.
        if (!/[A-Z]/.test(text)) return;
        // 1st pass: split lower→Upper boundaries (`getName` → `get Name`).
        // 2nd pass: split ACRONYM→Word boundaries (`JSONFile` → `JSON File`,
        //           `PgSQLInput` → `Pg SQL Input`).
        const parts = text
            .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
            .replace(/([A-Z]+)([A-Z][a-z])/g, "$1 $2")
            .split(/\s+/)
            .filter(Boolean)
            .map(function (p) { return p.toLowerCase(); });
        if (parts.length <= 1) return;
        for (const p of parts) {
            if (p !== orig) {
                tokens.push(makeToken(p, sliceStart, sliceLength, tokens.length, baseMetadata));
            }
        }
        // Adjacent bigrams so concatenated-substring queries like `fetchsize`
        // hit `defaultRowFetchSize` (which lunr would otherwise store as a
        // single stemmed token without `fetchsize` as a substring).
        for (let i = 0; i + 1 < parts.length; i++) {
            const bigram = parts[i] + parts[i + 1];
            if (bigram !== orig) {
                tokens.push(makeToken(bigram, sliceStart, sliceLength, tokens.length, baseMetadata));
            }
        }
    }

    function makeToken(text, sliceStart, sliceLength, index, baseMetadata) {
        const meta = Object.assign({}, baseMetadata, {
            position: [sliceStart, sliceLength],
            index: index,
        });
        return new lunr.Token(text, meta);
    }

    function ensureIndex() {
        if (index) return Promise.resolve();
        if (indexPromise) return indexPromise;
        indexPromise = fetch(base + "/search.json", { credentials: "same-origin" })
            .then(function (r) {
                if (!r.ok) throw new Error("search index fetch failed: " + r.status);
                return r.json();
            })
            .then(function (documents) {
                lookup = {};
                index = lunr(function () {
                    this.ref("uri");
                    this.field("title", { boost: 5 });
                    this.field("description", { boost: 2 });
                    this.field("categories");
                    this.field("content");
                    // Token positions are required to extract fragments around hits.
                    this.metadataWhitelist = ["position"];
                    // Replace lunr's default tokenizer so identifier-shaped
                    // strings (camelCase, snake_case, ALLCAPSToWord) get split
                    // into searchable parts in addition to their original form.
                    this.tokenizer = identifierAwareTokenizer;
                    for (const doc of documents) {
                        this.add(doc);
                        lookup[doc.uri] = doc;
                    }
                });
            });
        return indexPromise;
    }

    function performSearch(term) {
        // Tokenize the user's input the same way the index tokenizer does
        // (whitespace + hyphens + underscores), then build a wildcard-tolerant
        // lunr query so partial matches surface results while typing.
        const tokens = term.toLowerCase().split(IDENTIFIER_SEPARATOR).filter(Boolean);
        if (tokens.length === 0) return [];
        try {
            return index.query(function (q) {
                for (const t of tokens) {
                    const safe = t.replace(/[~^:*+\-]/g, "");
                    if (!safe) continue;
                    q.term(safe, { usePipeline: true, boost: 100 });
                    q.term(safe, { usePipeline: false, wildcard: lunr.Query.wildcard.TRAILING, boost: 10 });
                    // Both-sided wildcard turns the term into a substring match
                    // (`*fetchsize*`) so camelCase property names like
                    // `defaultRowFetchSize`, which lunr stores as a single
                    // token, are still discoverable by their internal parts.
                    // Guarded on length to avoid pulling half the index on
                    // short tokens (e.g. "ssl" would otherwise match anything
                    // containing those three letters).
                    if (safe.length >= 4) {
                        const both = lunr.Query.wildcard.LEADING | lunr.Query.wildcard.TRAILING;
                        q.term(safe, { usePipeline: false, wildcard: both, boost: 3 });
                    }
                    if (safe.length > 3) {
                        q.term(safe, { usePipeline: false, editDistance: 1, boost: 1 });
                    }
                }
            }).slice(0, MAX_RESULTS);
        } catch (e) {
            return [];
        }
    }

    function renderResults(term, results) {
        currentResults = results.map(function (r) {
            return Object.assign({ uri: r.ref, matchData: r.matchData }, lookup[r.ref]);
        });
        activeIndex = -1;

        clear(dropdown);

        if (currentResults.length === 0) {
            const empty = document.createElement("div");
            empty.className = "searchResults__empty";
            empty.textContent = "No results for “" + term + "”";
            dropdown.appendChild(empty);
        } else {
            const list = document.createElement("ul");
            list.className = "searchResults__list";
            list.setAttribute("role", "listbox");
            currentResults.forEach(function (doc, i) {
                list.appendChild(renderResult(doc, i));
            });
            dropdown.appendChild(list);
        }

        openDropdown();
    }

    function renderResult(doc, i) {
        const li = document.createElement("li");
        li.className = "searchResults__item";
        li.setAttribute("role", "option");
        li.id = "search-result-" + i;

        const link = document.createElement("a");
        link.className = "searchResults__link";
        link.href = doc.uri;
        link.addEventListener("mouseenter", function () { setActive(i); });

        const title = document.createElement("div");
        title.className = "searchResults__title";
        appendHighlighted(title, doc.title || doc.uri, matchedTokensForField(doc.matchData, "title"));
        link.appendChild(title);

        const fragments = buildFragments(doc.content || "", matchData(doc.matchData, "content"));
        if (fragments.length > 0) {
            const snippet = document.createElement("div");
            snippet.className = "searchResults__snippet";
            fragments.forEach(function (frag, idx) {
                if (idx > 0) {
                    const sep = document.createElement("span");
                    sep.className = "searchResults__ellipsis";
                    sep.textContent = " … ";
                    snippet.appendChild(sep);
                }
                appendHighlightedRanges(snippet, frag.text, frag.ranges);
            });
            link.appendChild(snippet);
        } else if (doc.description) {
            const snippet = document.createElement("div");
            snippet.className = "searchResults__snippet";
            snippet.textContent = doc.description;
            link.appendChild(snippet);
        }

        li.appendChild(link);
        return li;
    }

    // matchData :: lunr metadata for a single field — array of {term, start, length}
    function matchData(meta, field) {
        const out = [];
        if (!meta || !meta.metadata) return out;
        for (const token of Object.keys(meta.metadata)) {
            const fields = meta.metadata[token];
            const fieldMeta = fields && fields[field];
            if (!fieldMeta || !fieldMeta.position) continue;
            for (const [start, length] of fieldMeta.position) {
                out.push({ term: token, start: start, length: length });
            }
        }
        return out;
    }

    function matchedTokensForField(meta, field) {
        if (!meta || !meta.metadata) return [];
        const tokens = [];
        for (const token of Object.keys(meta.metadata)) {
            const fields = meta.metadata[token];
            if (fields && fields[field]) tokens.push(token);
        }
        return tokens;
    }

    // Build up to MAX_FRAGMENTS non-overlapping windows around real matches.
    // `hits` is sorted by start; we greedily pick hits whose windows don't
    // overlap an already-chosen one, then expand each to word boundaries.
    function buildFragments(text, hits) {
        if (!text || hits.length === 0) return [];
        const sorted = hits.slice().sort(function (a, b) { return a.start - b.start; });
        const windows = [];
        for (const hit of sorted) {
            if (windows.length >= MAX_FRAGMENTS) break;
            const winStart = Math.max(0, hit.start - FRAGMENT_RADIUS);
            const winEnd = Math.min(text.length, hit.start + hit.length + FRAGMENT_RADIUS);
            const prev = windows[windows.length - 1];
            if (prev && winStart <= prev.end) {
                prev.end = Math.max(prev.end, winEnd);
                prev.hits.push(hit);
            } else {
                windows.push({ start: winStart, end: winEnd, hits: [hit] });
            }
        }
        return windows.map(function (w) {
            const snapStart = snapToWordBoundary(text, w.start, -1);
            const snapEnd = snapToWordBoundary(text, w.end, 1);
            const slice = text.substring(snapStart, snapEnd);
            const ranges = w.hits
                .map(function (h) {
                    return { start: h.start - snapStart, length: h.length };
                })
                .filter(function (r) { return r.start >= 0 && r.start < slice.length; });
            return {
                text: (snapStart > 0 ? "… " : "") + slice + (snapEnd < text.length ? " …" : ""),
                ranges: ranges.map(function (r) {
                    return { start: r.start + (snapStart > 0 ? 2 : 0), length: r.length };
                })
            };
        });
    }

    function snapToWordBoundary(text, pos, dir) {
        const len = text.length;
        if (pos <= 0) return 0;
        if (pos >= len) return len;
        let i = pos;
        const limit = Math.min(40, dir < 0 ? i : len - i);
        for (let step = 0; step < limit; step++) {
            const ch = text.charAt(i);
            if (/\s/.test(ch)) return i;
            i += dir;
            if (i < 0 || i > len) break;
        }
        return pos;
    }

    // Append `text` to `parent`, wrapping any positions in `ranges` with <mark>.
    // Uses DOM APIs throughout to keep user-controllable strings out of innerHTML.
    function appendHighlightedRanges(parent, text, ranges) {
        if (!ranges || ranges.length === 0) {
            parent.appendChild(document.createTextNode(text));
            return;
        }
        const sorted = ranges.slice().sort(function (a, b) { return a.start - b.start; });
        let cursor = 0;
        for (const r of sorted) {
            if (r.start < cursor) continue;
            if (r.start > cursor) {
                parent.appendChild(document.createTextNode(text.substring(cursor, r.start)));
            }
            const mark = document.createElement("mark");
            mark.textContent = text.substr(r.start, r.length);
            parent.appendChild(mark);
            cursor = r.start + r.length;
        }
        if (cursor < text.length) {
            parent.appendChild(document.createTextNode(text.substring(cursor)));
        }
    }

    // Highlight by tokens (used for title where computing per-char ranges is
    // overkill — title matches are short and we just want each occurrence bolded).
    function appendHighlighted(parent, text, tokens) {
        if (!tokens || tokens.length === 0) {
            parent.appendChild(document.createTextNode(text));
            return;
        }
        const escaped = tokens
            .filter(Boolean)
            .map(function (t) { return t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"); });
        if (escaped.length === 0) {
            parent.appendChild(document.createTextNode(text));
            return;
        }
        const re = new RegExp("(" + escaped.join("|") + ")", "gi");
        let last = 0;
        let match;
        while ((match = re.exec(text)) !== null) {
            if (match.index > last) {
                parent.appendChild(document.createTextNode(text.substring(last, match.index)));
            }
            const mark = document.createElement("mark");
            mark.textContent = match[0];
            parent.appendChild(mark);
            last = match.index + match[0].length;
            if (match[0].length === 0) re.lastIndex++;
        }
        if (last < text.length) {
            parent.appendChild(document.createTextNode(text.substring(last)));
        }
    }

    function setActive(i) {
        const items = dropdown.querySelectorAll(".searchResults__item");
        items.forEach(function (el, idx) {
            const on = idx === i;
            el.classList.toggle("is-active", on);
            el.setAttribute("aria-selected", on ? "true" : "false");
        });
        activeIndex = i;
        if (i >= 0 && items[i]) {
            input.setAttribute("aria-activedescendant", items[i].id);
        } else {
            input.setAttribute("aria-activedescendant", "");
        }
    }

    function moveActive(delta) {
        const count = currentResults.length;
        if (count === 0) return;
        let next = activeIndex + delta;
        if (next < 0) next = count - 1;
        if (next >= count) next = 0;
        setActive(next);
        const items = dropdown.querySelectorAll(".searchResults__item");
        if (items[next]) items[next].scrollIntoView({ block: "nearest" });
    }

    function openDropdown() {
        dropdown.hidden = false;
        input.setAttribute("aria-expanded", "true");
    }

    function closeDropdown() {
        dropdown.hidden = true;
        input.setAttribute("aria-expanded", "false");
        input.setAttribute("aria-activedescendant", "");
        activeIndex = -1;
    }

    function renderError() {
        clear(dropdown);
        const err = document.createElement("div");
        err.className = "searchResults__empty";
        err.textContent = "Search is unavailable right now.";
        dropdown.appendChild(err);
        openDropdown();
    }

    function clear(node) {
        while (node.firstChild) node.removeChild(node.firstChild);
    }
})();
