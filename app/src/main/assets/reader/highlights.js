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
  const visibleRect = r => r.width > 0 && r.height > 0 && r.right > 1 && r.left < innerWidth - 1 &&
    r.bottom > 1 && r.top < innerHeight - 1;
  // A character's place: 1 on this page, 0 off it, null when it takes no room (hidden text).
  function onPage(node, index) {
    const r = document.createRange();
    r.setStart(node, index);
    r.setEnd(node, index + 1);
    const rects = [...r.getClientRects()].filter(x => x.width > 0 || x.height > 0);
    if (rects.length === 0) return null;
    return rects.some(visibleRect) ? 1 : 0;
  }
  function deepestLast(node) {
    while (node.lastChild) node = node.lastChild;
    return node;
  }
  // The first text node at or after a boundary, with the index to start from.
  function textAfter(node, offset) {
    if (node.nodeType === Node.TEXT_NODE) return [node, offset];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const child = node.childNodes[offset];
    if (child && child.nodeType === Node.TEXT_NODE) return [child, 0];
    walker.currentNode = child || deepestLast(node);
    const next = walker.nextNode();
    return next ? [next, 0] : null;
  }
  // The last text node at or before a boundary, with the index just after the place to start from.
  function textBefore(node, offset) {
    if (node.nodeType === Node.TEXT_NODE) return [node, offset];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const child = offset > 0 ? node.childNodes[offset - 1] : null;
    if (child) {
      const last = deepestLast(child);
      if (last.nodeType === Node.TEXT_NODE) return [last, last.data.length];
      walker.currentNode = last;
    } else walker.currentNode = node;
    const previous = walker.previousNode();
    return previous ? [previous, previous.data.length] : null;
  }
  // Where the first visible character after (forward) or before a boundary sits: 1, 0 or null
  // when there is none in the chapter. Spaces and hidden text are skipped.
  function neighbour(node, offset, forward) {
    const start = forward ? textAfter(node, offset) : textBefore(node, offset);
    if (!start) return null;
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let [text, index] = start;
    walker.currentNode = text;
    for (let nodes = 0; text && nodes < 200; nodes++) {
      if (forward) {
        for (; index < text.data.length; index++) {
          if (!/\S/.test(text.data[index])) continue;
          const place = onPage(text, index);
          if (place !== null) return place;
        }
        text = walker.nextNode();
        index = 0;
      } else {
        for (index -= 1; index >= 0; index--) {
          if (!/\S/.test(text.data[index])) continue;
          const place = onPage(text, index);
          if (place !== null) return place;
        }
        text = walker.previousNode();
        index = text ? text.data.length : 0;
      }
    }
    return null;
  }
  function pointKey(node, offset) {
    const parts = [];
    for (let n = node; n && n !== document.body; n = n.parentNode) {
      parts.unshift(n.parentNode ? [...n.parentNode.childNodes].indexOf(n) : 0);
    }
    return parts.join('/') + ':' + offset;
  }
  // Which end of the selection the owner is moving: the one that changed last.
  const moving = {start: null, end: null, side: 'end'};
  window.FolioSelection = {
    // The selection's moving end: edge 1 when the next visible character after it is on a later
    // page (it holds the page's last word, or has slipped past it), -1 when the one before it is on
    // an earlier page, else 0. With the selection's length and a key for that end and the page, so the reader
    // can tell a pause from a drag. Null when nothing is selected.
    edge() {
      const selection = window.getSelection();
      if (!selection || selection.rangeCount === 0 || selection.isCollapsed) {
        moving.start = moving.end = null;
        moving.side = 'end';
        return null;
      }
      const range = selection.getRangeAt(0);
      const startKey = pointKey(range.startContainer, range.startOffset);
      const endKey = pointKey(range.endContainer, range.endOffset);
      if (moving.end !== null) {
        if (endKey !== moving.end) moving.side = 'end';
        else if (startKey !== moving.start) moving.side = 'start';
      }
      moving.start = startKey;
      moving.end = endKey;
      const element = document.scrollingElement || document.documentElement;
      const scrolled = Math.abs(element.scrollLeft);
      const later = scrolled + innerWidth < element.scrollWidth - 1;
      const earlier = scrolled > 1;
      let edge = 0;
      if (moving.side === 'end' && later) {
        // The next visible character is off this page: the end rests on the page's last word, or
        // has already slipped past it into the next page's column (it jumps there as the finger
        // nears the corner).
        if (neighbour(range.endContainer, range.endOffset, true) === 0) edge = 1;
      } else if (moving.side === 'start' && earlier) {
        if (neighbour(range.startContainer, range.startOffset, false) === 0) edge = -1;
      }
      const key = (moving.side === 'end' ? endKey : startKey) + '@' + Math.round(scrolled);
      return {edge, length: range.toString().length, key};
    }
  };
})();
