import {build} from 'esbuild';
import {readFileSync,writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
await build({stdin:{contents:'export {generateXPointer, resolveXPointer, parseXPointer} from "@prose-reader/koreader";',resolveDir:fileURLToPath(new URL('.',import.meta.url))},bundle:true,format:'iife',globalName:'FolioPositions',minify:true,outfile:root+'app/src/main/assets/reader/positions.js',legalComments:'inline'});
for (const [name,file] of [['koreader','LICENSE'],['cfi','LICENCE'],['shared','LICENSE']]) writeFileSync(root+'app/src/main/assets/reader/licenses/Prose-'+name+'-MIT.txt',readFileSync(new URL('node_modules/@prose-reader/'+name+'/'+file, import.meta.url)));
