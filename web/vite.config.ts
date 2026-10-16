import { statSync } from "node:fs";
import { execSync } from "node:child_process";
import { defineConfig } from "vite";

// Uncompressed sizes of the files the loading bars track: GitHub Pages serves them gzipped, so the
// Content-Length header can't be used to measure progress.
const sizes = {
  runtime: statSync("node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.asyncify.wasm").size,
  plates: statSync("public/models/plates.onnx").size,
  faces: statSync("public/models/centerface.onnx").size,
};

function commit(): string {
  try {
    return execSync("git rev-parse --short HEAD").toString().trim();
  } catch {
    return "dev";
  }
}

export default defineConfig({
  // Relative paths: the app works from any folder, e.g. https://<user>.github.io/multiblur/.
  base: "./",
  define: {
    __FILE_SIZES__: JSON.stringify(sizes),
    __COMMIT__: JSON.stringify(commit()),
  },
  optimizeDeps: { exclude: ["onnxruntime-web"] },
  build: { target: "es2022", chunkSizeWarningLimit: 1500 },
});
