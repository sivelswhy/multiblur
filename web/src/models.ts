import * as ort from "onnxruntime-web/webgpu";
// The WebAssembly engine ONNX Runtime's WebGPU build runs on, served with the app instead of a CDN.
import runtimeURL from "onnxruntime-web/ort-wasm-simd-threaded.asyncify.wasm?url";

declare const __FILE_SIZES__: Record<AssetKey, number>;

export type AssetKey = "runtime" | "plates" | "faces";

export interface AssetState {
  phase: "waiting" | "downloading" | "initializing" | "ready" | "error";
  loaded: number;
  total: number;
  /** Read from the browser's cache instead of the network. */
  cached: boolean;
}

export const ASSETS: Record<AssetKey, { label: string; path: string }> = {
  runtime: { label: "Detection engine", path: runtimeURL },
  plates: { label: "License plate model", path: "models/plates.onnx" },
  faces: { label: "Face model", path: "models/centerface.onnx" },
};

export type Backend = "webgpu" | "wasm";

export interface Models {
  plates: ort.InferenceSession;
  faces: ort.InferenceSession;
  backend: Backend;
}

const CACHE_NAME = "multiblur-models";

/** Downloads a file, reporting progress, and keeps it in the Cache API so later visits load it instantly
 *  and offline. */
async function download(key: AssetKey, onProgress: (loaded: number, total: number, cached: boolean) => void): Promise<Uint8Array> {
  const { path } = ASSETS[key];
  const total = __FILE_SIZES__[key];
  const url = new URL(path, document.baseURI).href;
  // One entry per asset, whatever its (hashed) file name: storing a new version replaces the old one.
  const prefix = new URL(`cache/${key}?`, document.baseURI).href;
  const cacheKey = `${prefix}size=${total}&url=${encodeURIComponent(url)}`;
  let cache: Cache | undefined;
  try {
    cache = await caches.open(CACHE_NAME);
    const hit = await cache.match(cacheKey);
    if (hit) {
      const bytes = new Uint8Array(await hit.arrayBuffer());
      if (bytes.length === total) {
        onProgress(total, total, true);
        return bytes;
      }
    }
  } catch {
    cache = undefined; // no Cache API (private window, insecure context): download every time
  }

  const response = await fetch(url);
  if (!response.ok || !response.body) throw new Error(`${path}: HTTP ${response.status}`);
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.length;
    onProgress(Math.min(loaded, total), total, false);
  }
  const bytes = new Uint8Array(loaded);
  let position = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, position);
    position += chunk.length;
  }
  onProgress(total, total, false);

  if (cache) {
    try {
      // Drop older versions of this file before storing the new one.
      for (const request of await cache.keys()) {
        if (request.url.startsWith(prefix) && request.url !== cacheKey) await cache.delete(request);
      }
      await cache.put(cacheKey, new Response(bytes));
    } catch {
      // Storage full or denied: the app still works, it will just download again next time.
    }
  }
  return bytes;
}

/** Loads the engine and both models, in parallel, and creates their sessions on the GPU when possible. */
export async function loadModels(onState: (key: AssetKey, state: AssetState) => void): Promise<Models> {
  const fromCache: Record<AssetKey, boolean> = { runtime: false, plates: false, faces: false };
  const state = (key: AssetKey, phase: AssetState["phase"], loaded = 0) =>
    onState(key, { phase, loaded, total: __FILE_SIZES__[key], cached: fromCache[key] });
  const fetchAsset = async (key: AssetKey) => {
    try {
      return await download(key, (loaded, total, cached) => {
        fromCache[key] = cached;
        state(key, loaded >= total ? "initializing" : "downloading", loaded);
      });
    } catch (error) {
      state(key, "error");
      throw error;
    }
  };

  (Object.keys(ASSETS) as AssetKey[]).forEach((key) => state(key, "waiting"));
  const [runtime, plates, faces] = await Promise.all([fetchAsset("runtime"), fetchAsset("plates"), fetchAsset("faces")]);

  ort.env.wasm.wasmBinary = runtime;
  // Multithreading needs a cross-origin isolated page, which GitHub Pages can't provide.
  ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 1) : 1;
  state("runtime", "ready", runtime.length);

  const create = async (bytes: Uint8Array, backend: Backend) =>
    ort.InferenceSession.create(bytes, { executionProviders: [backend], graphOptimizationLevel: "all" });

  let backend: Backend = "wasm";
  let plateSession: ort.InferenceSession | undefined;
  if ("gpu" in navigator) {
    try {
      plateSession = await create(plates, "webgpu");
      backend = "webgpu";
    } catch (error) {
      console.warn("WebGPU unavailable, using WebAssembly", error);
    }
  }
  const sessions: Partial<Record<"plates" | "faces", ort.InferenceSession>> = {};
  for (const [key, bytes] of [["plates", plates], ["faces", faces]] as const) {
    try {
      sessions[key] = key === "plates" && plateSession ? plateSession : await create(bytes, backend);
      state(key, "ready", bytes.length);
    } catch (error) {
      state(key, "error");
      throw error;
    }
  }
  return { plates: sessions.plates!, faces: sessions.faces!, backend };
}

export { ort };
