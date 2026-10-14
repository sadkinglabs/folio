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
console.log('8 DOM interop fixtures passed (Unicode, whitespace, nested markup, shipped bundle). These are browser DOM tests, not KOReader device validation.');
