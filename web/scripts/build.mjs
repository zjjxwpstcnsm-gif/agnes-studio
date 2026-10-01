import { cp, mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const source = new URL('../src/', import.meta.url);
const destination = new URL('../dist/', import.meta.url);
const hash = createHash('sha256');
for (const name of (await readdir(source)).sort()) {
  hash.update(name);
  hash.update(await readFile(new URL(name, source)));
}
const assetDirectory = `src-${hash.digest('hex').slice(0, 12)}`;
await rm(destination, { recursive: true, force: true });
await mkdir(destination, { recursive: true });
await cp(source, new URL(assetDirectory, destination), { recursive: true });
await cp(new URL('../favicon.svg', import.meta.url), new URL('favicon.svg', destination));
const html = (await readFile(new URL('../index.html', import.meta.url), 'utf8'))
  .replaceAll('./src/', `./${assetDirectory}/`);
await writeFile(new URL('index.html', destination), html);
await writeFile(new URL('.nojekyll', destination), '');
console.log(`Built GitHub Pages site in web/dist with versioned assets: ${assetDirectory}`);
