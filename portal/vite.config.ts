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
    // C2-P6 HIGH: sourcemap was previously set to 'hidden' which still
    // emitted dist/assets/*.map files, and wrangler pages deploy ships
    // every file in dist/ — meaning the public Cloudflare URL would
    // serve the original TypeScript source to anyone who guessed the
    // hash-stamped filename. Setting to false omits the .map files
    // entirely. No Sentry today; revisit when we wire crash reporting
    // (uploadable maps via Sentry CLI is the standard pattern then).
    sourcemap: false,
    rollupOptions: {
      output: {
        // QC R1 perf: keep the main bundle lean by isolating the two
        // heaviest dependency trees in their own async chunks. Recharts
        // pulls in ~300KB (d3-shape, victory-vendor) and only matters
        // on the Dashboard route; @react-pdf/renderer is ~250KB and
        // only matters when the operator clicks Export PDF. Without
        // this both ended up in the eager bundle through tree shaking's
        // shared-module resolution, blowing past the 500KB main budget.
        manualChunks: {
          recharts: ['recharts'],
          pdf: ['@react-pdf/renderer'],
        },
      },
    },
  },
});
