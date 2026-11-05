// @ts-check
import { defineConfig } from "astro/config";
import sitemap from "@astrojs/sitemap";

// Served by GitHub Pages at https://yaedock.amrutlabs.in/, a custom domain
// set in the repository's Pages settings, so the site sits at the root.
export default defineConfig({
  site: "https://yaedock.amrutlabs.in",
  trailingSlash: "always",
  integrations: [sitemap()],
  vite: {
    // The page reads the version from yae_version.json at the repository root.
    server: { fs: { allow: [".."] } },
  },
});
