/* Folio highlights: the selected passage as KOReader pointers, and stored highlights placed as
   Readium text quotes. Needs positions.js. No Android interfaces, credentials or network access. */
(function () {
  // Characters of context kept on each side, so Readium finds the right copy of a repeated phrase.
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
  // a little context, all taken from that element's text.
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
    return {
      selector: selector(root),
      text: {
        before: all.slice(Math.max(0, start - CONTEXT), start),
        highlight,
        after: all.slice(end, end + CONTEXT)
      }
    };
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
  window.FolioHighlights = {
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

  // Selecting across pages. A chapter is one document laid out in columns, one page per screen,
  // so a selection can run on into the next page of the same chapter: the reader scrolls to it and
  // the selection stays. The next chapter is another document; a selection cannot run into it.
  window.FolioSelection = {
    // Whether text is selected, and whether this chapter has a page before (earlier) and after
    // (later) the one on screen, in reading order: a right-to-left book scrolls the other way, so
    // only the distance scrolled counts.
    state() {
      const selection = window.getSelection();
      const selected = !!selection && selection.rangeCount > 0 && !selection.isCollapsed;
      const element = document.scrollingElement || document.documentElement;
      const scrolled = Math.abs(element.scrollLeft);
      return {
        selected,
        earlier: scrolled > 1,
        later: scrolled + innerWidth < element.scrollWidth - 1
      };
    }
  };
})();
