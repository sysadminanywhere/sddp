import { defineConfig } from 'vite';

export default defineConfig({
  define: { 'process.env.NODE_ENV': JSON.stringify('production') },
  build: {
    outDir: '../src/main/resources/static/flow',
    emptyOutDir: false,
    lib: { entry: 'src/graph.jsx', formats: ['iife'], name: 'SddpGraph', fileName: () => 'graph.js' },
    rollupOptions: { output: { assetFileNames: 'graph.[ext]' } },
  },
});
