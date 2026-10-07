import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import sitemap from '@astrojs/sitemap';

export default defineConfig({
  site: 'https://digiwomb-dev.github.io/UnboundAir/',
  // Project Pages serve below a subpath, so every local URL needs the base.
  // If hosting ever moves to a host root (custom domain, organisation pages
  // repository), this line goes away together with the subpath in `site`.
  base: '/UnboundAir/',
  // Consciously off (condition 7 in #229): no speculative requests of any
  // kind until the DS-01 gate (#233) watches every deploy.
  prefetch: false,
  integrations: [
    starlight({
      title: 'UnboundAir',
      // Main language first: English is the default locale, so English URLs
      // carry no prefix and missing German translations would fall back to
      // English with a marker. With German-only content (until DO-16 fills
      // docs/en/) the English locale is empty by consequence, not by defect —
      // decided in #229 ("Sofort en"), the fallback check moves to #232.
      defaultLocale: 'en',
      locales: {
        en: { label: 'English', lang: 'en' },
        de: { label: 'Deutsch', lang: 'de' },
      },
      // No global `head` entries — condition 2 in #229. This object-level
      // `head` would apply to every page and is the hook Starlight's own
      // docs use to load analytics from a CDN. Per-page `head` frontmatter
      // stays allowed for tags that load nothing (e.g. `meta robots`).
      // The CSP lives in src/components/Head.astro instead.
      head: [],
      // No "Built with Starlight" credit line; social links only to the
      // project's own repository (condition 8 in #229).
      credits: false,
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/digiwomb-dev/UnboundAir',
        },
      ],
      // The "edit this page" URL is computed in
      // src/components/EditLink.astro instead of `editLink.baseUrl`:
      // Starlight would append the path below the site directory
      // (`src/content/docs/…`), which does not exist in the repository.
      components: {
        Footer: './src/components/Footer.astro',
        EditLink: './src/components/EditLink.astro',
        Head: './src/components/Head.astro',
      },
    }),
    sitemap({
      // The legal notice (#234, DS-02) carries noindex and must not be
      // listed: slug `legal-notice` in every locale. #234 conforms to this.
      filter: (page) => !page.includes('/legal-notice/'),
    }),
  ],
});
