import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile, readdir } from 'node:fs/promises';

test('published build versions the complete module graph and retains project-relative paths', async () => {
  execFileSync(process.execPath, ['scripts/build.mjs']);
  const html = await readFile('dist/index.html', 'utf8');
  const directory = html.match(/\.\/(src-[a-f0-9]{12})\/app\.js/)?.[1];
  assert.ok(directory, 'entry script points to content-versioned assets');
  assert.ok(html.includes(`./${directory}/styles.css`));
  assert.ok(!html.includes('./src/'));
  const files = await readdir(`dist/${directory}`);
  for (const file of ['app.js', 'api.js', 'queue.js', 'storage.js', 'styles.css', 'fonts.css']) assert.ok(files.includes(file));
  assert.ok((await readFile(`dist/${directory}/app.js`, 'utf8')).includes('from "./api.js"'));
  assert.ok((await readFile(`dist/${directory}/styles.css`, 'utf8')).includes('../favicon.svg'));
  assert.ok((await readFile('dist/favicon.svg', 'utf8')).includes('<svg'));
  assert.deepEqual((await readdir('dist')).sort(), ['.nojekyll', 'favicon.svg', 'index.html', directory].sort());
  execFileSync(process.execPath, ['scripts/build.mjs']);
  assert.equal(await readFile('dist/index.html', 'utf8'), html, 'same sources produce stable fingerprints');
});
