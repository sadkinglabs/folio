/* Folio highlights: the selected passage as KOReader pointers, and stored highlights placed as
   Readium text quotes. Needs positions.js. No Android interfaces, credentials or network access. */
(function () {
  // The fewest characters of context kept on each side; more when the passage repeats (see quote).
  const CONTEXT = 32;
  function selector(element) {
    const parts = [];
    while (element && element !== document.documentElement) {
      const tag = element.localName;
      const same = [...element.parentElement.children].filter(e => e.localName === tag);
      parts.unshift(tag + ':nth-of-type(' + (same.indexOf(element) + 1) + ')');
      element = element.parentElement;
    }
    return 'html > ' + parts.join(' > ');
  }
  // The range as Readium anchors a decoration: the smallest element holding it, and the text with
  // enough context, all taken from that element's text.
  //
  // Readium draws the first copy of the text whose context matches best. When the same words, with
  // the same CONTEXT characters around them, come more than once in the element (a repeated line or
  // refrain), every copy matches as well and the first one is drawn, which may be pages before the
  // highlight. So the context grows until this copy is the only one with it.
  function quote(range) {
    let root = range.commonAncestorContainer;
    if (root.nodeType !== Node.ELEMENT_NODE) root = root.parentElement;
    if (!root || root === document.documentElement) root = document.body;
    if (!root || !root.contains(range.startContainer) || !root.contains(range.endContainer)) return null;
    const lead = document.createRange();
    lead.setStart(root, 0);
    lead.setEnd(range.startContainer, range.startOffset);
    const all = root.textContent;
    const start = lead.toString().length;
    const end = start + range.toString().length;
    const highlight = all.slice(start, end);
    if (!highlight.trim()) return null;
    let context = CONTEXT, before, after;
    for (;;) {
      before = all.slice(Math.max(0, start - context), start);
      after = all.slice(end, end + context);
      const whole = before + highlight + after;
      // Unique once it runs from the element's first character to its last, at the latest.
      if (all.indexOf(whole) === all.lastIndexOf(whole)) break;
      context *= 2;
    }
    return {selector: selector(root), text: {before, highlight, after}};
  }
  function boundary(pointer) {
    if (typeof pointer !== 'string' || !pointer) return null;
    return FolioPositions.resolveXPointer(pointer, document) || null;
  }
  // A DOM range from KOReader's start and end pointers, or null when either is not in this page.
  function range(pos0, pos1) {
    const start = boundary(pos0), end = boundary(pos1);
    if (!start || !end) return null;
    const result = document.createRange();
    if (start.offset === undefined) result.setStartBefore(start.node);
    else result.setStart(start.node, start.offset);
    if (end.offset === undefined) result.setEndAfter(end.node);
    else result.setEnd(end.node, end.offset);
    return result.collapsed ? null : result;
  }
  // True when [outer] starts at or before [inner] and ends at or after it.
  function contains(outer, inner) {
    return outer.compareBoundaryPoints(Range.START_TO_START, inner) <= 0 &&
      outer.compareBoundaryPoints(Range.END_TO_END, inner) >= 0;
  }
  // A DOM range collapsed at one KOReader pointer, or null when it is not in this page.
  function point(pointer) {
    const found = boundary(pointer);
    if (!found) return null;
    const result = document.createRange();
    if (found.offset === undefined) result.setStartBefore(found.node);
    else result.setStart(found.node, found.offset);
    result.collapse(true);
    return result;
  }
  // Whether [element] starts a line of its own: a paragraph, heading, list item or the like.
  function block(element) {
    const display = getComputedStyle(element).display;
    return display !== 'inline' && display !== 'inline-block' && display !== 'contents';
  }
  // The range's text as the page shows it: a line break between paragraphs, like a selection's.
  function textOf(found) {
    const walker = document.createTreeWalker(found.commonAncestorContainer.nodeType === Node.TEXT_NODE ?
      found.commonAncestorContainer.parentNode : found.commonAncestorContainer, NodeFilter.SHOW_TEXT);
    let text = '', last = null, node;
    while ((node = walker.nextNode())) {
      if (!found.intersectsNode(node)) continue;
      const from = node === found.startContainer ? found.startOffset : 0;
      const to = node === found.endContainer ? found.endOffset : node.data.length;
      if (from >= to) continue;
      let owner = node.parentElement;
      while (owner && owner !== document.body && !block(owner)) owner = owner.parentElement;
      if (last && owner !== last) text += '\n';
      last = owner;
      text += node.data.slice(from, to);
    }
    return text.trim();
  }
  window.FolioHighlights = {
    // Which of two KOReader pointers in this page comes first: -1 when [a] is before [b], 0 when
    // they are the same place, 1 when after; null when either is not in this page. Used by
    // "Highlight from here…" to order the two ends the owner picked.
    compare(a, b) {
      const first = point(a), second = point(b);
      if (!first || !second) return null;
      return Math.sign(first.compareBoundaryPoints(Range.START_TO_START, second));
    },
    // The passage from KOReader pointer [pos0] to [pos1] in this page, the way selection()
    // reports a selected one, or null when it is not in this page. [items] as for selection().
    passage(pos0, pos1, items) {
      const found = range(pos0, pos1);
      if (!found) return null;
      const text = textOf(found);
      if (!text) return null;
      const inside = [];
      for (const item of items || []) {
        try {
          const other = range(item.pos0, item.pos1);
          if (other && contains(other, found)) inside.push(item.id);
        } catch (e) {
          // One bad pointer must not stop the others.
        }
      }
      return {pos0, pos1, text, inside};
    },
    // The selected passage in this page, [index] being its KOReader DocFragment, or null. [items]
    // are this chapter's highlights ({id, pos0, pos1}); `inside` lists the ones that hold the
    // whole selection, so selecting a highlighted passage opens it instead of adding another.
    selection(index, items) {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
      const selected = selection.getRangeAt(0);
      const pos0 = FolioPositions.generateXPointer({node: selected.startContainer, offset: selected.startOffset}, index);
      const pos1 = FolioPositions.generateXPointer({node: selected.endContainer, offset: selected.endOffset}, index);
      // The text as the page shows it, with line breaks between paragraphs.
      const text = selection.toString().trim();
      if (!pos0 || !pos1 || !text) return null;
      const inside = [];
      for (const item of items || []) {
        try {
          const found = range(item.pos0, item.pos1);
          if (found && contains(found, selected)) inside.push(item.id);
        } catch (e) {
          // One bad pointer must not stop the others.
        }
      }
      return {pos0, pos1, text, inside};
    },
    // Where each highlight sits in this page: {path, placed: {id: {selector, text}}}. Highlights
    // that do not resolve here are left out, never guessed.
    place(items) {
      const placed = {};
      for (const item of items || []) {
        try {
          const found = range(item.pos0, item.pos1);
          const anchor = found && quote(found);
          if (anchor) placed[item.id] = anchor;
        } catch (e) {
          // One bad pointer must not stop the others.
        }
      }
      let path = location.pathname;
      try { path = decodeURIComponent(path); } catch (e) {}
      return {path, placed};
    }
  };
})();
