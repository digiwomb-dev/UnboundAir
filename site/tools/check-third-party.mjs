// DS-01 gate: the published site must contact no third party.
//
// Runs after `astro build` against the generated output directory and fails
// on anything a visitor's browser would fetch from a foreign host. Text links
// (<a href>) are treated differently from loaded resources: an anchor to the
// repository occurs on every page and is harmless, while a <script src> is
// not. Without that distinction the check would cry wolf until someone
// disables it.
//
// What this check cannot find (read before trusting it):
// - Runtime-assembled URLs ('htt' + 'ps://…') or anything a minifier rewrites.
// - What the browser actually does: only a real page load with a network
//   capture proves that, which needs a browser and more moving parts.
// - A compromised dependency acting after the build, or only in the visitor.
// - What the build runner itself sends. The ASTRO_TELEMETRY_DISABLED
//   assertion below covers Astro's telemetry specifically, not outbound
//   traffic in general.
// - The CSP from #229 is the complement acting at the visitor's end. Neither
//   replaces the other.
//
// Usage: node check-third-party.mjs [dist-dir] (default: ./dist, relative to
// the site directory). Offline: reads files, calls nothing.

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

// The only absolute URL allowed for loaded resources: the site itself.
const ALLOW_LIST = ['https://digiwomb-dev.github.io/UnboundAir/'];

// Fail hard on these anywhere, so a typo in the allow list cannot hide them.
const HARD_FAIL = [
  'fonts.googleapis.com',
  'fonts.gstatic.com',
  'cdn.jsdelivr.net',
  'unpkg.com',
  'cdnjs.cloudflare.com',
  'gravatar.com',
  'googletagmanager.com',
  'google-analytics.com',
  'cdn.usefathom.com',
  'plausible.io',
  'algolia.net',
  'algolianet.com',
  'youtube.com',
  'youtu.be',
  'vimeo.com',
];

// Attribute/pattern collectors over one line: [isLoadedResource, value].
function collect(line) {
  const found = [];
  // Loaded resources.
  for (const m of line.matchAll(
    /<(script|link|img|source|video|audio|embed|iframe|track)[^>]*?(?:src|href)\s*=\s*"([^"]*)"/gi,
  )) {
    found.push([true, m[2]]);
  }
  for (const m of line.matchAll(/<form[^>]*?action\s*=\s*"([^"]*)"/gi)) {
    found.push([true, m[1]]);
  }
  for (const m of line.matchAll(/<[^>]*?srcset\s*=\s*"([^"]*)"/gi)) {
    for (const candidate of m[1].split(',')) {
      const url = candidate.trim().split(/\s+/)[0];
      if (url) found.push([true, url]);
    }
  }
  for (const m of line.matchAll(/url\(\s*["']?([^"'()]*?)["']?\s*\)/gi)) {
    found.push([true, m[1]]);
  }
  for (const m of line.matchAll(/@import\s+(?:url\(\s*["']?([^"'()]*?)["']?\s*\)|["']([^"']+)["'])/gi)) {
    found.push([true, m[1] ?? m[2]]);
  }
  // Text links: anchors only, checked against HARD_FAIL but never the allow list.
  for (const m of line.matchAll(/<a[^>]*?href\s*=\s*"([^"]*)"/gi)) {
    found.push([false, m[1]]);
  }
  return found;
}

function isAbsolute(target) {
  return /^(https?:)?\/\//i.test(target);
}

function allowed(target) {
  const normalized = target.startsWith('//') ? 'https:' + target : target;
  return ALLOW_LIST.some((prefix) => normalized.startsWith(prefix));
}

function hardFail(target) {
  const lower = target.toLowerCase();
  return HARD_FAIL.some((host) => lower.includes(host));
}

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
let checked = 0;

for (const extension of ['.html', '.css']) {
  for (const file of listFiles(distDir, extension)) {
    const name = relative(distDir, file);
    const lines = readFileSync(file, 'utf8').split('\n');
    lines.forEach((line, index) => {
      if (extension === '.css' && /@font-face/i.test(line)) {
        const urls = [...line.matchAll(/url\(\s*["']?([^"'()]*?)["']?\s*\)/gi)].map((m) => m[1]);
        for (const url of urls) {
          if (/^https?:/i.test(url)) {
            findings.push(`${name}:${index + 1} -> @font-face with external url(${url})`);
          }
        }
      }
      for (const [loaded, target] of collect(line)) {
        const path = target.split('#')[0];
        if (!path || path.startsWith('data:') || path.startsWith('mailto:') || path.startsWith('#')) continue;
        if (!isAbsolute(path)) {
          if (loaded) checked++;
          continue;
        }
        if (hardFail(path)) {
          findings.push(`${name}:${index + 1} -> ${loaded ? 'loaded' : 'linked'} ${path}`);
          continue;
        }
        if (loaded) {
          checked++;
          if (!allowed(path)) {
            findings.push(`${name}:${index + 1} -> loaded ${path}`);
          }
        }
      }
    });
  }
}

// Condition 6 of the review is about the runner, not the visitor, but it is
// checked here so removing it fails the build: Astro's telemetry posts from
// the build job, and isCI silences only the notice.
const workflow = join(import.meta.dirname, '..', '..', '.github', 'workflows', 'docs.yml');
if (!readFileSync(workflow, 'utf8').includes('ASTRO_TELEMETRY_DISABLED')) {
  findings.push('.github/workflows/docs.yml -> ASTRO_TELEMETRY_DISABLED missing from the job environment');
}

console.log(`checked ${checked} loaded targets, ${findings.length} findings`);
for (const finding of findings) console.log(finding);
process.exit(findings.length === 0 ? 0 : 1);
