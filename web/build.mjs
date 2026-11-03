// Bundles the bpmn-js editor into a single self-contained index.html
// (JS, CSS and fonts inlined) that the plugin loads into a JCEF browser,
// plus THIRD-PARTY-NOTICES.txt with the licenses of every npm package in the bundle.
import * as esbuild from 'esbuild';
import { mkdir, readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const MIT_TERMS = `Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the
following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
OR OTHER DEALINGS IN THE SOFTWARE.`;

const outArg = process.argv.find(a => a.startsWith('--outdir='));
const outDir = path.resolve(outArg ? outArg.slice('--outdir='.length) : 'dist');

const result = await esbuild.build({
  entryPoints: ['src/main.js'],
  bundle: true,
  minify: true,
  format: 'iife',
  target: 'chrome120',
  write: false,
  metafile: true,
  outdir: 'out',
  loader: { '.eot': 'empty', '.woff': 'dataurl', '.woff2': 'dataurl', '.ttf': 'dataurl', '.svg': 'dataurl' },
  logLevel: 'warning'
});

const output = ext => {
  const file = result.outputFiles.find(f => f.path.endsWith(ext));
  if (!file) throw new Error(`esbuild produced no ${ext} output`);
  return file.text;
};

// `</script` / `</style` inside the bundles would terminate the inline tags early
const js = output('.js').replace(/<\/script/gi, '<\\/script');
const css = output('.css').replace(/<\/style/gi, '<\\/style');

const template = await readFile('src/index.html', 'utf8');
const html = template
  .replace('<!-- inline:css -->', () => `<style>${css}</style>`)
  .replace('<!-- inline:js -->', () => `<script>${js}</script>`);

await mkdir(outDir, { recursive: true });
await writeFile(path.join(outDir, 'index.html'), html);
console.log(`bpmn editor bundled -> ${path.join(outDir, 'index.html')} (${(html.length / 1024).toFixed(0)} KiB)`);

const packages = await bundledPackages(result.metafile);
await writeFile(path.join(outDir, 'THIRD-PARTY-NOTICES.txt'), await notices(packages));
console.log(`third-party notices for ${packages.length} packages -> ${path.join(outDir, 'THIRD-PARTY-NOTICES.txt')}`);

/** Package directories (`node_modules/x` or `node_modules/@scope/x`) that contributed files to the bundle. */
async function bundledPackages(metafile) {
  const dirs = new Set();
  for (const input of Object.keys(metafile.inputs)) {
    const match = input.match(/^(.*node_modules\/(?:@[^/]+\/)?[^/]+)\//);
    if (match) dirs.add(match[1]);
  }
  return [...dirs].sort();
}

async function notices(dirs) {
  const sections = [];
  for (const dir of dirs) {
    const pkg = JSON.parse(await readFile(path.join(dir, 'package.json'), 'utf8'));
    const licenseFile = (await readdir(dir)).find(f => /^(licen[sc]e|copying)(\.(md|txt))?$/i.test(f));
    let text;
    if (licenseFile) {
      text = (await readFile(path.join(dir, licenseFile), 'utf8')).trim();
    } else if (pkg.license === 'MIT') {
      // Some packages declare MIT in package.json without shipping the text
      const author = typeof pkg.author === 'object' ? pkg.author.name : pkg.author;
      text = `Copyright (c) ${author || `${pkg.name} authors`}\n\n${MIT_TERMS}`;
    } else {
      throw new Error(`${pkg.name} (${pkg.license}) ships no license file; add its license text by hand`);
    }
    sections.push(`${pkg.name} ${pkg.version} (${pkg.license})\n${'-'.repeat(72)}\n${text}`);
  }
  return 'The BPMN editor bundles the following open source packages.\n\n' + sections.join('\n\n\n') + '\n';
}

