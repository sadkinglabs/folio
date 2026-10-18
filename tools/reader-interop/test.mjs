import {JSDOM} from 'jsdom';
import {readFileSync} from 'node:fs';
import {generateXPointer,resolveXPointer} from '@prose-reader/koreader';
import assert from 'node:assert/strict';
const fixtures = [
 ['<p>Plain paragraph.</p>',0,5],
 ['<p>A <em>nested</em> sentence.</p>',1,3],
 ['<p>One😀two🌍three.</p>',0,7],
 ['<p>White   space\n\twith words.</p>',0,12],
 ['<p>日本語の文章。次の段落。</p>',0,4],
 ['<p>First.</p><p>Second paragraph.</p>',1,4],
 ['<p>مرحبا بالقراءة</p>',0,5],
];
for (const [html,nodeIndex,offset] of fixtures) {
 const doc = new JSDOM('<!doctype html><html><body>'+html+'</body></html>').window.document;
 const walker=doc.createTreeWalker(doc.body,4);const nodes=[];while(walker.nextNode())nodes.push(walker.currentNode);
 const node=nodes[nodeIndex];const pointer=generateXPointer({node,offset},2);
 assert.ok(pointer); const resolved=resolveXPointer(pointer,doc);assert.equal(resolved.node,node);assert.equal(resolved.offset,offset);
 assert.equal(resolveXPointer('/body/DocFragment[3]/body/p[999]/text().1',doc),undefined);
}
// Test the shipped bundle rather than just npm imports; no network/native interface is needed.
const dom = new JSDOM('<html><body><p>A😀passage with enough text to resolve.</p></body></html>',{runScripts:'outside-only'});
dom.window.eval(readFileSync(new URL('../../app/src/main/assets/reader/positions.js',import.meta.url),'utf8'));
dom.window.eval(readFileSync(new URL('../../app/src/main/assets/reader/bridge.js',import.meta.url),'utf8'));
const pointer=dom.window.FolioPositions.generateXPointer({node:dom.window.document.querySelector('p').firstChild,offset:3},0);
const anchor=dom.window.FolioReader.resolve(pointer);assert.equal(anchor.selector,'html > body:nth-of-type(1) > p:nth-of-type(1)');assert.equal(anchor.text.highlight,'passage with enough text to resolve.');
assert.equal(dom.window.FolioReader.resolve('/body/DocFragment/body/p[99]/text().0'),null);
// "Highlight from here…" with the shipped highlights.js: two picked words, read as a selection
// each, ordered and joined into one passage, then placed the way Readium draws it.
function reader(paragraphs) {
 const page = new JSDOM('<html><body><h1>Chapter two</h1>'+paragraphs.map(p=>'<p>'+p+'</p>').join('')+'</body></html>',{runScripts:'outside-only'});
 page.window.eval(readFileSync(new URL('../../app/src/main/assets/reader/positions.js',import.meta.url),'utf8'));
 page.window.eval(readFileSync(new URL('../../app/src/main/assets/reader/highlights.js',import.meta.url),'utf8'));
 return page.window;
}
// The [nth] copy (0-based) of [word] in paragraph [p], selected as a long-press selects it.
function pick(win, p, word, nth) {
 const text = win.document.getElementsByTagName('p')[p].firstChild;
 let at = -1; for (let i = 0; i <= nth; i++) at = text.data.indexOf(word, at + 1);
 assert.ok(at >= 0, word);
 const range = win.document.createRange(); range.setStart(text, at); range.setEnd(text, at + word.length);
 const selection = win.getSelection(); selection.removeAllRanges(); selection.addRange(range);
 const picked = win.FolioHighlights.selection(2, []);
 selection.removeAllRanges();
 return {picked, range};
}
// ReaderActivity.saveFromHere: compare both pairs, take the earlier start and the later end.
function fromHere(win, a, b) {
 const starts = win.FolioHighlights.compare(a.pos0, b.pos0), ends = win.FolioHighlights.compare(a.pos1, b.pos1);
 return win.FolioHighlights.passage(starts <= 0 ? a.pos0 : b.pos0, ends >= 0 ? a.pos1 : b.pos1, []);
}
// Where Readium 3.4 draws a placed quote (TextQuoteAnchor in readium-reflowable.js): every exact
// copy of the text in the selector's element, scored by how well the text before and after it
// matches; the first best one wins. Only exact context counts here, which orders the copies the
// same way: the right one is the only one whose context matches fully, or it ties with others.
function readiumDraws(win, quote) {
 const all = win.document.querySelector(quote.selector).textContent;
 let best = null;
 for (let at = all.indexOf(quote.text.highlight); at !== -1; at = all.indexOf(quote.text.highlight, at + 1)) {
  const end = at + quote.text.highlight.length;
  const score = (all.slice(Math.max(0, at - quote.text.before.length), at) === quote.text.before ? 1 : 0) +
   (all.slice(end, end + quote.text.after.length) === quote.text.after ? 1 : 0);
  if (!best || score > best.score) best = {score, at};
 }
 return {root: win.document.querySelector(quote.selector), at: best && best.at};
}
// Where the passage from [first] to [last] (DOM ranges) starts in [root]'s text.
function offsetIn(win, root, first) {
 const lead = win.document.createRange(); lead.setStart(root, 0); lead.setEnd(first.startContainer, first.startOffset);
 return lead.toString().length;
}
function checkFromHere(win, first, last, label) {
 for (const [a, b] of [[first, last], [last, first]]) {
  const passage = fromHere(win, a.picked, b.picked);
  assert.ok(passage, label);
  // The saved pointers are the first word's start and the last word's end, in either pick order.
  assert.equal(passage.pos0, first.picked.pos0, label);
  assert.equal(passage.pos1, last.picked.pos1, label);
  const expected = win.document.createRange();
  expected.setStart(first.range.startContainer, first.range.startOffset);
  expected.setEnd(last.range.endContainer, last.range.endOffset);
  assert.equal(passage.text.replace(/\s+/g, ''), expected.toString().replace(/\s+/g, ''), label);
  // Readium draws it over those words, not over an earlier copy of them.
  const placed = win.FolioHighlights.place([{id: 'h', pos0: passage.pos0, pos1: passage.pos1}]).placed.h;
  assert.ok(placed, label);
  const drawn = readiumDraws(win, placed);
  assert.equal(drawn.at, offsetIn(win, drawn.root, first.range), label + ': drawn at another copy');
 }
}
{
 // Every word distinct: no repeated text can hide where either end lands.
 const word = n => 'w' + n.toString(36).padStart(3, '0') + 'x';
 const paragraphs = [0, 1, 2].map(p => Array.from({length: 120}, (_, i) => word(p * 120 + i)).join(' ') + '.');
 const win = reader(paragraphs);
 checkFromHere(win, pick(win, 0, word(117), 0), pick(win, 1, word(129), 0), 'distinct words over two paragraphs');
 checkFromHere(win, pick(win, 1, word(150), 0), pick(win, 1, word(170), 0), 'distinct words in one paragraph');
}
{
 // The emulator's book: each paragraph repeats one sentence six times, so a passage inside it, with
 // 32 characters around it, comes more than once in the paragraph.
 const filler = ' The rows ran down to the river and back up the hill again.'.repeat(6);
 const win = reader(Array.from({length: 4}, (_, i) => 'Chapter two paragraph ' + (i + 1) + ' with enough text.' + filler));
 checkFromHere(win, pick(win, 1, 'river', 3), pick(win, 1, 'again', 4), 'repeated sentence in one paragraph');
 checkFromHere(win, pick(win, 1, 'hill', 5), pick(win, 2, 'rows', 1), 'repeated sentence over two paragraphs');
 checkFromHere(win, pick(win, 2, 'down', 2), pick(win, 2, 'down', 2), 'one repeated word');
}
console.log('8 DOM interop fixtures (Unicode, whitespace, nested markup, shipped bundle) and 5 Highlight from here fixtures (either order, distinct and repeated words) passed. These are browser DOM tests, not KOReader device validation.');
