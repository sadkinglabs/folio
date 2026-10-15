/* Folio DOM adapter. No Android interfaces, credentials or network access. */
(function () {
  const visible = rect => rect.width > 0 && rect.height > 0 && rect.right > 1 && rect.left < innerWidth - 1 && rect.bottom > 1 && rect.top < innerHeight - 1;
  // Hit-test near the page's top-left to find a text node close to the first visible one.
  function hint() {
    for (const y of [4, 16, 40, innerHeight / 4, innerHeight / 2]) {
      for (const x of [4, 16, innerWidth / 4, innerWidth / 2]) {
        let node = null;
        if (document.caretPositionFromPoint) node = document.caretPositionFromPoint(x, y)?.offsetNode;
        else if (document.caretRangeFromPoint) node = document.caretRangeFromPoint(x, y)?.startContainer;
        if (node && node.nodeType === Node.TEXT_NODE && document.body.contains(node)) return node;
      }
    }
    return null;
  }
  function firstText() {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    // Start a little before the hint so the exact search below still finds the first visible
    // character; without a hint (or in non-layout DOMs) scan from the start as before.
    let node = null;
    const near = hint();
    if (near) {
      walker.currentNode = near;
      for (let i = 0; i < 64 && walker.previousNode(); i++);
      node = walker.currentNode;
    } else node = walker.nextNode();
    for (let visited = 0; node && visited < 8000; node = walker.nextNode(), visited++) {
      if (!node.data.trim() || node.data.length > 65536 || /^(SCRIPT|STYLE|NOSCRIPT)$/.test(node.parentElement?.tagName || '')) continue;
      const range = document.createRange();
      range.selectNodeContents(node);
      if (![...range.getClientRects()].some(visible)) continue;
      // Prefix visibility is monotonic even across paginated columns. Locate first visible char.
      let low = 1, high = node.data.length;
      while (low < high) {
        const mid = Math.floor((low + high) / 2);
        range.setStart(node, 0); range.setEnd(node, mid);
        if ([...range.getClientRects()].some(visible)) high = mid; else low = mid + 1;
      }
      return {node, offset: low - 1};
    }
  }
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
  window.FolioReader = {
    // Whether the page has scrolled to the locator's progression. Readium reports a restored
    // locator before the page gets there; capturing earlier would name the chapter's first line.
    settled(expected) {
      if (expected === null || expected === undefined) return true;
      const el = document.scrollingElement || document.documentElement;
      const horizontal = el.scrollWidth > el.clientWidth + 1;
      const size = horizontal ? el.scrollWidth : el.scrollHeight;
      if (!size) return true;
      const offset = Math.abs(horizontal ? el.scrollLeft : el.scrollTop);
      const page = (horizontal ? innerWidth : innerHeight) / size;
      return Math.abs(offset / size - expected) <= page + 0.01;
    },
    capture(index) {
      const position = firstText();
      return position ? FolioPositions.generateXPointer(position, index) || '' : '';
    },
    resolve(pointer) {
      const position = FolioPositions.resolveXPointer(pointer, document);
      if (!position) return null;
      let node = position.node;
      const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
      const offset = position.offset || 0;
      const text = node.nodeType === Node.TEXT_NODE ? {
        before: node.data.slice(Math.max(0, offset - 64), offset),
        highlight: node.data.slice(offset, offset + 64),
        after: node.data.slice(offset + 64, offset + 128)
      } : {};
      return { selector: selector(element), text };
    }
  };
})();
