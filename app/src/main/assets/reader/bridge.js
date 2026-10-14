/* Folio DOM adapter. No Android interfaces, credentials or network access. */
(function () {
  const visible = rect => rect.width > 0 && rect.height > 0 && rect.right > 1 && rect.left < innerWidth - 1 && rect.bottom > 1 && rect.top < innerHeight - 1;
  function firstText() {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let node, visited = 0;
    while ((node = walker.nextNode()) && ++visited <= 8000) {
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
