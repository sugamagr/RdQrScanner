import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: false,
  },
  build: {
    target: 'es2022',
    // P6β NITPICK: 'hidden' generates the .map files so we can still
    // debug Sentry stack traces if we ever wire one up, but does NOT
    // ship the //# sourceMappingURL comment in the JS bundle — so a
    // visitor opening DevTools doesn't get a tree-view of the original
    // TypeScript source. The .map files in dist/ should be excluded
    // from the Cloudflare deploy (added a wrangler pages publish
    // glob in the deploy script).
    sourcemap: 'hidden',
  },
});
