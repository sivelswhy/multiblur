import { Detector } from "./detector";
import { ASSETS, type AssetKey, type AssetState, loadModels } from "./models";
import { DEFAULTS, type Options, loadOptions, saveOptions } from "./options";
import { type Result, decodeImage, isVideo, processImage, processVideo } from "./process";

declare const __COMMIT__: string;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const megabytes = (bytes: number) => `${(bytes / 1e6).toFixed(bytes < 1e7 ? 1 : 0)} MB`;

// ------------------------------------------------------------------------------------------------
// Models and their loading bars
// ------------------------------------------------------------------------------------------------

const modelRows = new Map<AssetKey, HTMLLIElement>();
for (const [key, asset] of Object.entries(ASSETS) as [AssetKey, (typeof ASSETS)[AssetKey]][]) {
  const row = document.createElement("li");
  row.className = "model-row";
  row.innerHTML = `<div class="meta"><span class="name"></span><span class="status">Waiting…</span></div>
    <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100"><span></span></div>`;
  row.querySelector(".name")!.textContent = asset.label;
  row.querySelector(".bar")!.setAttribute("aria-label", asset.label);
  $("model-list").append(row);
  modelRows.set(key, row);
}

function showAsset(key: AssetKey, state: AssetState) {
  const row = modelRows.get(key)!;
  row.dataset.phase = state.phase;
  const fraction = state.total ? state.loaded / state.total : 0;
  const status = {
    waiting: "Waiting…",
    downloading: `${megabytes(state.loaded)} / ${megabytes(state.total)}`,
    initializing: "Preparing…",
    ready: state.cached ? `Ready · from cache` : `Ready · ${megabytes(state.total)}`,
    error: "Failed",
  }[state.phase];
  row.querySelector(".status")!.textContent = status;
  (row.querySelector(".bar > span") as HTMLElement).style.width = `${Math.round(fraction * 100)}%`;
  row.querySelector(".bar")!.setAttribute("aria-valuenow", String(Math.round(fraction * 100)));
}

let detector: Detector | null = null;

loadModels(showAsset)
  .then((models) => {
    detector = new Detector(models);
    const badge = $("backend");
    badge.hidden = false;
    badge.textContent = models.backend === "webgpu" ? "Running on the GPU" : "Running on the CPU (slower)";
    badge.classList.toggle("slow", models.backend === "wasm");
    badge.title = models.backend === "webgpu" ? "WebGPU" : "WebAssembly: this browser doesn't offer WebGPU";
    void runQueue();
  })
  .catch((error) => {
    console.error(error);
    const message = $("model-error");
    message.hidden = false;
    message.textContent = `The models couldn't be loaded (${error instanceof Error ? error.message : error}). Check your connection and reload the page.`;
  });

// ------------------------------------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------------------------------------

let options = loadOptions();
let replacement: ImageBitmap | null = null;
const settings = document.querySelector<HTMLElement>(".settings")!;

const formats: Partial<Record<keyof Options, (v: number) => string>> = {
  mosaicSize: (v) => `${v} px`,
  plateConfidence: (v) => v.toFixed(2),
  faceThreshold: (v) => v.toFixed(2),
  plateMaskScale: (v) => `×${v.toFixed(2)}`,
  faceMaskScale: (v) => `×${v.toFixed(2)}`,
};

function showOptions() {
  for (const input of settings.querySelectorAll<HTMLInputElement | HTMLSelectElement>("[name]")) {
    const value = options[input.name as keyof Options];
    if (input instanceof HTMLInputElement && input.type === "checkbox") input.checked = Boolean(value);
    else if (input instanceof HTMLInputElement && input.type === "radio") input.checked = input.value === value;
    else input.value = String(value);
  }
  for (const output of settings.querySelectorAll<HTMLOutputElement>("output[data-for]")) {
    const key = output.dataset.for as keyof Options;
    output.textContent = formats[key]?.(options[key] as number) ?? String(options[key]);
  }
  for (const element of settings.querySelectorAll<HTMLElement>("[data-show]")) {
    element.hidden = element.dataset.show !== options.mode;
  }
}

settings.addEventListener("input", (event) => {
  const input = event.target as HTMLInputElement | HTMLSelectElement;
  if (!input.name) return;
  const key = input.name as keyof Options;
  const current = DEFAULTS[key];
  const value =
    input instanceof HTMLInputElement && input.type === "checkbox"
      ? input.checked
      : typeof current === "number"
        ? Number(input.value)
        : input.value;
  options = { ...options, [key]: value };
  saveOptions(options);
  showOptions();
});

$("reset").addEventListener("click", () => {
  options = { ...DEFAULTS };
  saveOptions(options);
  showOptions();
});

$<HTMLInputElement>("replacement").addEventListener("change", async (event) => {
  const file = (event.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    replacement?.close();
    replacement = await decodeImage(file);
    $("replacement-name").textContent = file.name;
  } catch {
    replacement = null;
    $("replacement-name").textContent = "Can't read this image";
  }
});

showOptions();

// ------------------------------------------------------------------------------------------------
// Queue
// ------------------------------------------------------------------------------------------------

type Status = "queued" | "processing" | "done" | "error" | "canceled";

interface Item {
  file: File;
  status: Status;
  element: HTMLLIElement;
  result?: Result;
  url?: string;
  cancel?: () => void;
}

const queue: Item[] = [];
let running = false;

function addFiles(files: Iterable<File>) {
  for (const file of files) {
    if (!file.type.startsWith("image/") && !isVideo(file)) continue;
    const element = document.createElement("li");
    element.className = "item";
    element.innerHTML = `<button type="button" class="thumb" aria-label="Open the result"><canvas width="224" height="144"></canvas></button>
      <div class="body"><div class="title"></div><div class="detail"></div><div class="bar"><span></span></div></div>
      <div class="actions"></div>`;
    element.querySelector(".title")!.textContent = file.name;
    const item: Item = { file, status: "queued", element };
    queue.push(item);
    $("queue").append(element);
    element.querySelector(".thumb")!.addEventListener("click", () => openViewer(item));
    render(item, "Waiting…");
    void thumbnail(item);
  }
  updateQueueBar();
  void runQueue();
}

/** Shows the original photo in the thumbnail until its result is ready. */
async function thumbnail(item: Item) {
  if (isVideo(item.file)) return;
  try {
    const image = await decodeImage(item.file);
    if (item.status === "queued") drawThumb(item, image);
    image.close();
  } catch {
    // Unreadable images get an error when processed.
  }
}

function drawThumb(item: Item, source: CanvasImageSource & { width: number; height: number }) {
  const canvas = item.element.querySelector("canvas")!;
  const ctx = canvas.getContext("2d")!;
  const scale = Math.max(canvas.width / source.width, canvas.height / source.height);
  const w = source.width * scale, h = source.height * scale;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(source, (canvas.width - w) / 2, (canvas.height - h) / 2, w, h);
}

function render(item: Item, detail: string, fraction?: number) {
  const { element } = item;
  element.dataset.status = item.status;
  element.querySelector(".detail")!.textContent = detail;
  const bar = element.querySelector(".bar") as HTMLElement;
  bar.classList.toggle("indeterminate", item.status === "processing" && fraction === undefined);
  if (fraction !== undefined) (bar.firstElementChild as HTMLElement).style.width = `${Math.round(fraction * 100)}%`;

  const actions = element.querySelector(".actions")!;
  actions.replaceChildren();
  if (item.status === "done" && item.url && item.result) {
    const link = document.createElement("a");
    link.className = "button";
    link.href = item.url;
    link.download = item.result.name;
    link.textContent = "Download";
    actions.append(link);
  }
  if (item.status === "processing" && item.cancel) {
    actions.append(button("Stop", "secondary", () => item.cancel?.()));
  }
  if (item.status !== "processing") {
    actions.append(button("Remove", "link", () => remove(item)));
  }
}

function button(label: string, style: string, action: () => void) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = style;
  b.textContent = label;
  b.addEventListener("click", action);
  return b;
}

function remove(item: Item) {
  const index = queue.indexOf(item);
  if (index >= 0) queue.splice(index, 1);
  if (item.url) URL.revokeObjectURL(item.url);
  item.element.remove();
  updateQueueBar();
}

function summary(result: Result, options: Options): string {
  const parts: string[] = [];
  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? "" : "s"}`;
  if (options.maskPlates) parts.push(plural(result.plates, "plate"));
  if (options.maskFaces) parts.push(plural(result.faces, "face"));
  return `${parts.join(", ") || "Nothing to mask"} · ${megabytes(result.blob.size)}`;
}

function updateQueueBar() {
  $("queue-bar").hidden = queue.length === 0;
  const done = queue.filter((i) => i.status === "done").length;
  const waiting = queue.filter((i) => i.status === "queued" || i.status === "processing").length;
  $("queue-summary").textContent = waiting
    ? `${done} of ${queue.length} done`
    : `${queue.length} file${queue.length === 1 ? "" : "s"}`;
  $("download-all").hidden = done < 2;
}

async function runQueue() {
  if (running || !detector) return;
  running = true;
  try {
    for (let item = next(); item; item = next()) await processItem(item, detector);
  } finally {
    running = false;
  }
}

const next = () => queue.find((i) => i.status === "queued");

async function processItem(item: Item, detector: Detector) {
  // Settings are read when a file starts, so changing them affects the files that follow.
  const used = { ...options };
  if (!used.maskPlates && !used.maskFaces) {
    item.status = "error";
    render(item, "Choose what to mask: plates, faces or both.");
    updateQueueBar();
    return;
  }
  if (used.mode === "image" && !replacement) {
    item.status = "error";
    render(item, "Choose a replacement image under Style.");
    updateQueueBar();
    return;
  }
  const video = isVideo(item.file);
  if (video && typeof VideoEncoder === "undefined") {
    item.status = "error";
    render(item, "This browser can't encode video. Try a recent Chrome, Edge or Safari.");
    updateQueueBar();
    return;
  }

  item.status = "processing";
  render(item, video ? "Starting…" : "Detecting…");
  updateQueueBar();
  const started = performance.now();
  let canceled = false;
  try {
    const process = video ? processVideo : processImage;
    const result = await process(item.file, detector, used, replacement, {
      progress: (fraction) => {
        if (!video) return;
        const elapsed = (performance.now() - started) / 1000;
        const remaining = fraction > 0.02 ? (elapsed / fraction) * (1 - fraction) : NaN;
        render(item, `${Math.round(fraction * 100)} %${Number.isFinite(remaining) ? ` · about ${duration(remaining)} left` : ""}`, fraction);
      },
      preview: (frame) => drawThumb(item, frame),
      onCancel: (cancel) => {
        item.cancel = () => {
          canceled = true;
          cancel();
        };
        render(item, "Starting…");
      },
    });
    item.result = result;
    item.url = URL.createObjectURL(result.blob);
    item.status = "done";
    render(item, summary(result, used), 1);
    if (result.warning) {
      const warning = document.createElement("div");
      warning.className = "detail warning";
      warning.textContent = result.warning;
      item.element.querySelector(".body")!.append(warning);
    }
  } catch (error) {
    console.error(error);
    item.status = canceled ? "canceled" : "error";
    render(item, canceled ? "Stopped" : error instanceof Error ? error.message : String(error), 0);
  }
  item.cancel = undefined;
  updateQueueBar();
}

function duration(seconds: number): string {
  if (seconds < 60) return `${Math.max(1, Math.round(seconds))} s`;
  const minutes = Math.round(seconds / 60);
  return minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

function openViewer(item: Item) {
  if (item.status !== "done" || !item.url) return;
  const body = $("viewer-body");
  const media = isVideo(item.file) ? Object.assign(document.createElement("video"), { controls: true, autoplay: true }) : new Image();
  media.src = item.url;
  body.replaceChildren(media);
  $<HTMLDialogElement>("viewer").showModal();
}

$<HTMLDialogElement>("viewer").addEventListener("close", () => $("viewer-body").replaceChildren());

$("download-all").addEventListener("click", () => {
  for (const item of queue) {
    if (item.status !== "done" || !item.url || !item.result) continue;
    const link = document.createElement("a");
    link.href = item.url;
    link.download = item.result.name;
    link.click();
  }
});

$("clear").addEventListener("click", () => {
  for (const item of [...queue]) if (item.status !== "queued" && item.status !== "processing") remove(item);
});

// ------------------------------------------------------------------------------------------------
// Drag and drop
// ------------------------------------------------------------------------------------------------

const drop = $("drop");
$<HTMLInputElement>("picker").addEventListener("change", (event) => {
  const input = event.target as HTMLInputElement;
  if (input.files) addFiles(input.files);
  input.value = "";
});
// Files can be dropped anywhere on the page.
window.addEventListener("dragover", (event) => {
  event.preventDefault();
  drop.classList.add("over");
});
window.addEventListener("dragleave", (event) => {
  if (!event.relatedTarget) drop.classList.remove("over");
});
window.addEventListener("drop", (event) => {
  event.preventDefault();
  drop.classList.remove("over");
  if (event.dataTransfer?.files) addFiles(event.dataTransfer.files);
});

// Asks before leaving while files are being processed: results only live in this page.
window.addEventListener("beforeunload", (event) => {
  if (queue.some((i) => i.status === "processing" || i.status === "queued")) event.preventDefault();
});

$("commit").textContent = __COMMIT__;
