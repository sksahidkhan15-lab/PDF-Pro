import { defineConfig } from 'vite';

function resolveBasePath() {
  if (process.env.VITE_BASE_PATH) {
    const raw = process.env.VITE_BASE_PATH.trim();
    if (!raw || raw === './') return './';
    const withLeading = raw.startsWith('/') ? raw : `/${raw}`;
    return withLeading.endsWith('/') ? withLeading : `${withLeading}/`;
  }
  if (process.env.GITHUB_REPOSITORY) {
    const repo = process.env.GITHUB_REPOSITORY.split('/')[1];
    if (repo) return `/${repo}/`;
  }
  return './';
}

export default defineConfig({
  base: resolveBasePath(),
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'es2022',
  },
  optimizeDeps: {
    exclude: ['pdfjs-dist'],
  },
});
