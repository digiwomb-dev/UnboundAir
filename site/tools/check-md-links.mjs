// DS-03 gate: no relative Markdown link survives into the published site.
//
// Runs after `astro build` against the generated output directory and fails
// on any `href` that still ends in `.md` and is not one of the deliberate
// links into the repository. The damage this prevents is invisible at the
// next build: a reader clicks "Reference: configuration.md" and gets a 404,
// because the browser resolved it against `/UnboundAir/de/operations/`.
//
// Why a gate and not a test in the suite — the same reason as DS-01
// (docs/internal/teststrategie.md): the property is about built output,
// which only `astro build` produces, and `./gradlew build` must not require
// Node. The companion check is RepositoryHygieneTest, which proves every
// relative link *resolves to a file* — that belongs in the test suite,
// because it reads the repository. Neither replaces the other: a link can
// resolve in the repository and still 404 on the site, which is exactly the
// defect DS-03 exists for.
//
// Two kinds of `.md` href are legitimate and are allowed by prefix:
// - `…/edit/dev/docs/…`  the "edit this page" link (EditLink.astro)
// - `…/blob/dev/…`       a target outside the published tree, rewritten by
//                        src/plugins/rewrite-md-links.mjs (case 2 of DS-03)
// Both are absolute links into this project's own repository. Allowing them
// by prefix rather than by "contains github.com" keeps a stray link to some
// other repository's Markdown file a finding.
//
// What this check cannot find (read before trusting it):
// - A link that points at a page that does not exist. The rewrite produces
//   a well-formed URL from any input; whether the target is there is the
//   filesystem's question, and RepositoryHygieneTest asks it.
// - A link rewritten to the wrong locale. Both locales produce valid URLs,
//   so only reading one page of each proves the locale is carried through —
//   that is the manual half of the acceptance criterion.
// - Anything assembled in the browser at runtime.
//
// Usage: node check-md-links.mjs [dist-dir] (default: ./dist, relative to
// the site directory). Offline: reads files, calls nothing.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ALLOWED_PREFIXES = [
  'https://github.com/digiwomb-dev/UnboundAir/edit/',
  'https://github.com/digiwomb-dev/UnboundAir/blob/',
];

function listFiles(dir, extension) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      out.push(...listFiles(path, extension));
    } else if (path.endsWith(extension)) {
      out.push(path);
    }
  }
  return out;
}

const distDir = process.argv[2] ?? join(import.meta.dirname, '..', 'dist');
const findings = [];
let allowed = 0;

for (const file of listFiles(distDir, '.html')) {
  const name = relative(distDir, file);
  readFileSync(file, 'utf8')
    .split('\n')
    .forEach((line, index) => {
      for (const match of line.matchAll(/href\s*=\s*"([^"]*)"/gi)) {
        const target = match[1];
        // The fragment is irrelevant: `page.md#section` is as broken as
        // `page.md`, and a fragment on a real page never ends in `.md`.
        const path = target.split('#')[0];
        if (!path.endsWith('.md')) continue;
        if (ALLOWED_PREFIXES.some((prefix) => path.startsWith(prefix))) {
          allowed++;
          continue;
        }
        findings.push(`${name}:${index + 1} -> ${target}`);
      }
    });
}

if (findings.length > 0) {
  console.error(
    'DS-03: relative Markdown links reached the published site.\n' +
      'These resolve against the page URL in a browser and 404. The texts\n' +
      'keep relative links on purpose; the build rewrites them. A finding\n' +
      'here means the rewrite did not see this link — check\n' +
      'site/src/plugins/rewrite-md-links.mjs.\n',
  );
  for (const finding of findings) console.error(`  ${finding}`);
  process.exit(1);
}

console.log(`checked ${distDir}, ${allowed} repository links, 0 findings`);
