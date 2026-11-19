# PDF Studio Pro

A PhoText.ai-inspired smart one-tap PDF text editor available as both a **Static Web App (GitHub Pages Ready)** and a **Native Android App (Kotlin + Jetpack Compose)**.

## Core Capabilities

- **One-Tap Live Text Editing**: Tap any existing text on a PDF page, type your replacement, see live changes directly on the canvas with background reconstruction and matched typography, and confirm with **✓**.
- **Automatic Style Matching & Multilingual Support**: Extracts font families (`Sans`, `Serif`, `Mono`, `Noto Sans Bengali`), font sizes, bold/italic styles, colors, and rotation angles.
- **Background Restoration**: Reconstructs white paper, uniform solid-color cards/badges, and edge-interpolated complex/gradient backgrounds.
- **Scanned PDF OCR**: Automatically detects text on image-only scanned pages (`Tesseract.js` on Web, on-device `ML Kit` on Android).
- **Real PDF Export & Programmatic Verification**: Strips replaced original text from content streams, embeds Unicode TrueType fonts (including Bengali), preserves untouched pages/images, and programmatically reopens the exported PDF to verify replacements.
- **100% Client-Side Privacy**: No backend server, no API keys, and local project storage (`IndexedDB` on Web, private `filesDir` on Android).

## Web / GitHub Pages Setup

```bash
npm install
npm run dev        # Local development server
npm test           # Run Node PDF export & Bengali Unicode verification tests
npm run build      # Builds static production bundle into dist/
```

### GitHub Pages Base Path Configuration
`vite.config.js` automatically detects `GITHUB_REPOSITORY` in GitHub Actions (or `VITE_BASE_PATH`), and falls back to `'./'` locally:
- Custom repo subpath: `VITE_BASE_PATH=/your-repo-name/ npm run build`
- Workflow included at `.github/workflows/deploy.yml` (builds `dist/` and deploys automatically via `actions/deploy-pages@v4` on push to `main`).

## Native Android Setup

- Compile debug APK: `gradle assembleDebug`
- Run Robolectric test suite: `gradle :app:testDebugUnitTest`
