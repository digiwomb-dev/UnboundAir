/*
 * DS-03: a relative Markdown link must work in both places it is read —
 * in the repository on GitHub and on the published site.
 *
 * The documentation keeps relative `.md` targets (`configuration.md`,
 * `../../CONTRIBUTING.de.md`) for two reasons that cannot be argued away:
 *
 * 1. The DO-15 link-target rule compares a German file against its English
 *    translation and requires the targets to be *equal* after normalizing
 *    `.de.md` to `.md`. Writing per-locale absolute URLs into the texts
 *    (`/UnboundAir/de/…` against `/UnboundAir/en/…`) makes that rule red by
 *    construction — and the rule is right to do so: a rewritten link target
 *    is exactly what it exists to catch.
 * 2. GitHub renders relative `.md` links natively. An absolute site URL
 *    would send a reader who already has the repository open out to the web
 *    to read the file next to the one they are looking at.
 *
 * Starlight does not rewrite these, so the built output carried
 * `href="configuration.md"` six times and `href="../../CONTRIBUTING.md"`
 * twice — correct on GitHub, 404 on the site, because the browser resolves
 * them against `/UnboundAir/de/operations/`.
 *
 * The build is the only place that knows which of the two readers is being
 * served, so the rewrite happens here rather than in the texts.
 *
 * Three cases, decided in DS-03 rather than left to this file:
 *
 * - Sibling inside the published locale -> the locale's page URL below the
 *   base path. The locale comes from the file being processed, so the same
 *   source text yields a German link under docs/de/ and an English one
 *   under docs/en/. That is the whole point of doing this at build time.
 * - Target outside the published tree (`../../CONTRIBUTING.de.md`,
 *   `../internal/plan.md`) -> the GitHub blob URL on `dev`. There is no site
 *   URL to point at: the root files are not part of the collection and
 *   docs/internal/ is deliberately unpublished (DO-13). Sending the reader
 *   to the repository is the honest answer; a dead relative link is not.
 * - Fragments survive. Heading slugs come from translated heading text, so
 *   the fragment belongs to the target page and is not ours to touch.
 *
 * What this does not do: it does not check that a target exists —
 * RepositoryHygieneTest already does that against the filesystem, which
 * fails in `./gradlew test` rather than in a site build. And it leaves
 * absolute URLs alone, including the one on the DS-01 gate's allow list.
 *
 * **Why an mdast plugin and not a remark one.** Astro 7 processes Markdown
 * with Sätteri; `markdown.remarkPlugins` still exists but runs on a unified
 * processor that is no longer installed, and using it would mean adding
 * `@astrojs/markdown-remark` to run the whole pipeline a second way. The
 * Sätteri plugin interface dispatches by node type (`link`, `definition`)
 * and hands the visitor the document URL, which is everything this needs.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = '/UnboundAir';
const LOCALES = ['de', 'en'];
const BLOB = 'https://github.com/digiwomb-dev/UnboundAir/blob/dev/';

// The collection is fed through locale symlinks (Spike E), so the paths the
// processor reports are site-internal. The repository-relative path is
// recovered from the segment below the collection, not from the filesystem —
// resolving the symlink would land on the same place but make this depend on
// where the repository is checked out.
const COLLECTION = 'src/content/docs/';

/** Splits `target.md#fragment` into its path and its fragment. */
function splitFragment(target) {
  const hash = target.indexOf('#');
  return hash === -1 ? [target, ''] : [target.slice(0, hash), target.slice(hash)];
}

/**
 * The repository-relative directory of the file being processed, or null when
 * the document is not one of the locale trees.
 *
 * What matters for resolving a relative target is where the *source* file
 * lives in the repository (`docs/de/`), because that is what the author wrote
 * the link against — not where the processor found it.
 */
function sourceDirOf(fileURL) {
  if (fileURL === undefined) return null;
  const filePath = fileURLToPath(fileURL).split(path.sep).join('/');
  const index = filePath.indexOf(COLLECTION);
  if (index === -1) return null;
  const belowCollection = filePath.slice(index + COLLECTION.length);
  const locale = belowCollection.split('/')[0];
  if (!LOCALES.includes(locale)) return null;
  return path.posix.dirname('docs/' + belowCollection);
}

/**
 * Turns a repository-relative `.md` path into the URL it should carry.
 *
 * Inside `docs/<locale>/` the file is a published page, so it becomes a site
 * URL with a trailing slash — Starlight builds pages as directories, and
 * omitting the slash costs a redirect at best. Everything else has no page
 * and goes to the repository.
 */
function urlFor(repoPath) {
  const match = /^docs\/(de|en)\/(.+)\.md$/.exec(repoPath);
  if (match) {
    const [, locale, slug] = match;
    // `index.md` is the locale root, not a page named "index".
    const page = slug === 'index' ? '' : `${slug}/`;
    return `${BASE}/${locale}/${page}`;
  }
  return BLOB + repoPath;
}

/**
 * The rewrite itself, shared by both node types.
 *
 * `definition` is what a reference-style link (`[text][label]`) resolves
 * through; it carries a `url` like `link` does. Covering both means the rule
 * does not depend on which of the two spellings an author happened to use.
 */
function rewrite(node, context) {
  const sourceDir = sourceDirOf(context.fileURL);
  if (sourceDir === null) return;

  const url = node.url;
  if (typeof url !== 'string' || url === '') return;
  // Absolute URLs, protocol-relative ones, in-page anchors and
  // already-absolute paths are none of our business.
  if (/^[a-z][a-z0-9+.-]*:/i.test(url)) return;
  if (url.startsWith('//') || url.startsWith('#') || url.startsWith('/')) return;

  const [target, fragment] = splitFragment(url);
  if (!target.endsWith('.md')) return;

  const repoPath = path.posix.normalize(path.posix.join(sourceDir, target));
  context.setProperty(node, 'url', urlFor(repoPath) + fragment);
}

export const rewriteMdLinks = {
  name: 'unboundair-rewrite-md-links',
  link: rewrite,
  definition: rewrite,
};
