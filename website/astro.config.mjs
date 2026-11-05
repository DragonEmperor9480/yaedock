// @ts-check
import { defineConfig } from "astro/config";
import sitemap from "@astrojs/sitemap";

// Served by GitHub Pages at https://dragonemperor9480.github.io/yaedock/.
// With a custom domain, set `site` to it and drop `base`.
export default defineConfig({
  site: "https://dragonemperor9480.github.io",
  base: "/yaedock",
  trailingSlash: "always",
  integrations: [sitemap()],
  vite: {
    // The page reads the version from yae_version.json at the repository root.
    server: { fs: { allow: [".."] } },
  },
});
