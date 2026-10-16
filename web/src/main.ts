import { Detector } from "./detector";
import { type Key, type Language, language, onLanguageChange, setLanguage, t, translate } from "./i18n";
import { ASSETS, type AssetKey, type AssetState, type Backend, loadModels } from "./models";
import { DEFAULTS, type Options, loadOptions, saveOptions } from "./options";
import { type Result, decodeImage, isVideo, processImage, processVideo } from "./process";

declare const __COMMIT__: string;

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const megabytes = (bytes: number) => `${(bytes / 1e6).toFixed(bytes < 1e7 ? 1 : 0)} MB`;

const reducedMotion = () => matchMedia("(prefers-reduced-motion: reduce)").matches;

const CHECK = `<svg class="check-icon" viewBox="0 0 16 16" aria-hidden="true"><path d="m3.5 8.5 3 3 6-7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>`;

// ------------------------------------------------------------------------------------------------
// Toolbar: the small title appears once the large one scrolls under the bar
// ------------------------------------------------------------------------------------------------

new IntersectionObserver(
  ([entry]) => document.querySelector(".toolbar")!.classList.toggle("scrolled", !entry.isIntersecting),
  { rootMargin: "-52px 0px 0px 0px" },
).observe(document.querySelector("h1")!);

/** Moves a segmented control's thumb under its checked option. */
function updateSegmented(control: HTMLElement) {
  const inputs = [...control.querySelectorAll<HTMLInputElement>("input[type=radio]")];
  control.style.setProperty("--count", String(inputs.length));
  control.style.setProperty("--index", String(Math.max(0, inputs.findIndex((i) => i.checked))));
}

// ------------------------------------------------------------------------------------------------
// Language
// ------------------------------------------------------------------------------------------------

const languageControl = $("language");
for (const input of languageControl.querySelectorAll<HTMLInputElement>("input")) {
  input.checked = input.value === language();
  input.addEventListener("change", () => setLanguage(input.value as Language));
}
updateSegmented(languageControl);

function refreshTexts() {
  translate();
  updateSegmented(languageControl);
  for (const [key, state] of assetStates) showAsset(key, state);
  if (backend) showBackend(backend);
  if (replacementName) $("replacement-name").textContent = replacementName;
  showOptions();
  for (const item of queue) render(item);
  updateQueueBar();
}

onLanguageChange(refreshTexts);

// ------------------------------------------------------------------------------------------------
// Models and their loading bars
// ------------------------------------------------------------------------------------------------

const modelRows = new Map<AssetKey, HTMLLIElement>();
const assetStates = new Map<AssetKey, AssetState>();
for (const key of Object.keys(ASSETS) as AssetKey[]) {
  const row = document.createElement("li");
  row.className = "model-row";
  row.innerHTML = `<div class="meta"><span class="name" data-i18n="models.${key}"></span><span class="status"></span></div>
    <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" data-i18n-aria-label="models.${key}"><span></span></div>`;
  $("model-list").append(row);
  modelRows.set(key, row);
}

function showAsset(key: AssetKey, state: AssetState) {
  assetStates.set(key, state);
  const row = modelRows.get(key)!;
  row.dataset.phase = state.phase;
  const fraction = state.total ? state.loaded / state.total : 0;
  const status = row.querySelector(".status")!;
  if (state.phase === "ready") {
    status.innerHTML = CHECK;
    status.append(state.cached ? t("models.cached") : `${t("models.ready")} · ${megabytes(state.total)}`);
  } else {
    status.textContent = {
      waiting: t("models.waiting"),
      downloading: `${megabytes(state.loaded)} / ${megabytes(state.total)}`,
      initializing: t("models.preparing"),
      error: t("models.failed"),
    }[state.phase];
  }
  const bar = row.querySelector<HTMLElement>(".bar")!;
  bar.style.setProperty("--progress", String(fraction));
  bar.setAttribute("aria-valuenow", String(Math.round(fraction * 100)));
}

let detector: Detector | null = null;
let backend: Backend | null = null;

function showBackend(value: Backend) {
  const pill = $("backend");
  pill.hidden = false;
  pill.textContent = value === "webgpu" ? t("backend.gpu") : t("backend.cpu");
  pill.title = value === "webgpu" ? t("backend.gpuTitle") : t("backend.cpuTitle");
  pill.classList.toggle("slow", value === "wasm");
}

let modelError: unknown = null;

loadModels(showAsset)
  .then((models) => {
    detector = new Detector(models);
    backend = models.backend;
    showBackend(backend);
    for (const item of queue) if (item.status === "queued") render(item);
    void runQueue();
  })
  .catch((error) => {
    console.error(error);
    modelError = error;
    showModelError();
  });

function showModelError() {
  if (!modelError) return;
  const message = $("model-error");
  message.hidden = false;
  message.textContent = t("models.error", { error: modelError instanceof Error ? modelError.message : String(modelError) });
}
onLanguageChange(showModelError);

// ------------------------------------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------------------------------------

let options = loadOptions();
let replacement: ImageBitmap | null = null;
let replacementName: string | null = null;
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
  for (const control of settings.querySelectorAll<HTMLElement>(".segmented")) updateSegmented(control);
}

settings.addEventListener("input", (event) => {
  const input = event.target as HTMLInputElement | HTMLSelectElement;
  if (!input.name || !(input.name in DEFAULTS)) return;
  const key = input.name as keyof Options;
  const value =
    input instanceof HTMLInputElement && input.type === "checkbox"
      ? input.checked
      : typeof DEFAULTS[key] === "number"
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
    replacementName = file.name;
    $("replacement-name").textContent = file.name;
  } catch {
    replacement = null;
    replacementName = null;
    $("replacement-name").textContent = t("settings.unreadableImage");
  }
});

// ------------------------------------------------------------------------------------------------
// Queue
// ------------------------------------------------------------------------------------------------

type Status = "queued" | "processing" | "done" | "error" | "canceled";

interface Item {
  file: File;
  status: Status;
  element: HTMLLIElement;
  /** The status line, rebuilt in the current language on each render. */
  detail: () => string;
  fraction?: number;
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
    element.innerHTML = `<button type="button" class="thumb" data-i18n-aria-label="item.open"><canvas width="224" height="144"></canvas></button>
      <div class="body"><div class="title"></div><div class="detail" aria-live="polite"></div><div class="bar"><span></span></div></div>
      <div class="actions"></div>`;
    element.querySelector(".title")!.textContent = file.name;
    translate(element);
    const item: Item = { file, status: "queued", element, detail: () => t(detector ? "item.queued" : "item.waiting") };
    queue.push(item);
    $("queue").append(element);
    element.querySelector(".thumb")!.addEventListener("click", () => openViewer(item));
    render(item);
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

function render(item: Item) {
  const { element } = item;
  element.dataset.status = item.status;
  element.querySelector(".detail")!.textContent = item.detail();
  const bar = element.querySelector<HTMLElement>(".bar")!;
  bar.classList.toggle("indeterminate", item.status === "processing" && item.fraction === undefined);
  bar.style.setProperty("--progress", String(item.fraction ?? 0));

  const warning = element.querySelector(".warning");
  if (item.result?.warning) {
    const line = warning ?? element.querySelector(".body")!.appendChild(Object.assign(document.createElement("div"), { className: "warning" }));
    line.textContent = t(item.result.warning);
  } else {
    warning?.remove();
  }

  const actions = element.querySelector(".actions")!;
  actions.replaceChildren();
  if (item.status !== "processing") actions.append(button("item.remove", "plain", () => remove(item)));
  if (item.status === "processing" && item.cancel) actions.append(button("item.stop", "gray small", () => item.cancel?.()));
  if (item.status === "done" && item.url && item.result) {
    const { result } = item;
    // Phones save to Photos through the share sheet; a download would end up in Files.
    const file = new File([result.blob], result.name, { type: result.blob.type });
    if (matchMedia("(pointer: coarse)").matches && navigator.canShare?.({ files: [file] })) {
      actions.append(button("item.share", "tinted small", () => void navigator.share({ files: [file] }).catch(() => {})));
    }
    const link = document.createElement("a");
    link.className = "button filled small";
    link.href = item.url;
    link.download = result.name;
    link.textContent = t("item.download");
    actions.append(link);
  }
}

function button(label: Key, style: string, action: () => void) {
  const b = document.createElement("button");
  b.type = "button";
  b.className = `button ${style}`;
  b.textContent = t(label);
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

function summary(result: Result, used: Options): string {
  const parts: string[] = [];
  if (used.maskPlates) parts.push(t("item.plates", { count: result.plates }));
  if (used.maskFaces) parts.push(t("item.faces", { count: result.faces }));
  return `${parts.join(", ") || t("item.nothing")} · ${megabytes(result.blob.size)}`;
}

function updateQueueBar() {
  $("queue-bar").hidden = queue.length === 0;
  const done = queue.filter((i) => i.status === "done").length;
  const waiting = queue.some((i) => i.status === "queued" || i.status === "processing");
  $("queue-summary").textContent = waiting ? t("queue.done", { done, total: queue.length }) : t("queue.files", { count: queue.length });
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

function fail(item: Item, key: Key) {
  item.status = "error";
  item.detail = () => t(key);
  render(item);
  updateQueueBar();
}

async function processItem(item: Item, detector: Detector) {
  // Settings are read when a file starts, so changing them affects the files that follow.
  const used = { ...options };
  if (!used.maskPlates && !used.maskFaces) return fail(item, "error.noTarget");
  if (used.mode === "image" && !replacement) return fail(item, "error.noReplacement");
  const video = isVideo(item.file);
  if (video && typeof VideoEncoder === "undefined") return fail(item, "error.noEncoder");

  item.status = "processing";
  item.detail = () => t(video ? "item.starting" : "item.detecting");
  render(item);
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
        const percent = Math.round(fraction * 100);
        item.fraction = fraction;
        item.detail = () =>
          Number.isFinite(remaining) ? t("item.progressLeft", { percent, time: duration(remaining) }) : t("item.progress", { percent });
        render(item);
      },
      preview: (frame) => drawThumb(item, frame),
      onCancel: (cancel) => {
        item.cancel = () => {
          canceled = true;
          cancel();
        };
        render(item);
      },
    });
    item.result = result;
    item.url = URL.createObjectURL(result.blob);
    item.status = "done";
    item.fraction = 1;
    item.detail = () => summary(result, used);
  } catch (error) {
    console.error(error);
    item.status = canceled ? "canceled" : "error";
    const message = error instanceof Error ? error.message : String(error);
    item.detail = () => (canceled ? t("item.stopped") : message);
  }
  item.cancel = undefined;
  render(item);
  updateQueueBar();
}

function duration(seconds: number): string {
  if (seconds < 60) return `${Math.max(1, Math.round(seconds))} s`;
  const minutes = Math.round(seconds / 60);
  return minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
}

// ------------------------------------------------------------------------------------------------
// Viewer: grows out of the thumbnail and shrinks back into it
// ------------------------------------------------------------------------------------------------

const viewer = $<HTMLDialogElement>("viewer");
let viewerSource: HTMLElement | null = null;

/** The transform that maps `media` onto `thumb`, for a FLIP animation. */
function fromThumb(media: HTMLElement, thumb: HTMLElement): Keyframe {
  const a = thumb.getBoundingClientRect(), b = media.getBoundingClientRect();
  const dx = a.left + a.width / 2 - (b.left + b.width / 2), dy = a.top + a.height / 2 - (b.top + b.height / 2);
  return { transform: `translate(${dx}px, ${dy}px) scale(${a.width / b.width}, ${a.height / b.height})`, opacity: 0.4 };
}

function openViewer(item: Item) {
  if (item.status !== "done" || !item.url) return;
  const media = isVideo(item.file)
    ? Object.assign(document.createElement("video"), { controls: true, autoplay: true, playsInline: true })
    : new Image();
  media.src = item.url;
  $("viewer-body").replaceChildren(media);
  viewerSource = item.element.querySelector(".thumb");
  viewer.showModal();
  const animate = () => {
    if (reducedMotion() || !viewerSource) {
      media.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 200 });
      return;
    }
    media.animate([fromThumb(media, viewerSource), { transform: "none", opacity: 1 }], {
      duration: 400,
      easing: "cubic-bezier(0.2, 0.8, 0.2, 1)",
    });
  };
  if (media instanceof HTMLImageElement) media.decode().then(animate, animate);
  else media.addEventListener("loadedmetadata", animate, { once: true });
}

// Closing runs the opening path in reverse, back into the thumbnail.
viewer.addEventListener("cancel", (event) => {
  event.preventDefault();
  closeViewer();
});
viewer.querySelector("form")!.addEventListener("submit", (event) => {
  event.preventDefault();
  closeViewer();
});
viewer.addEventListener("click", (event) => {
  if (event.target === viewer) closeViewer();
});

function closeViewer() {
  const media = $("viewer-body").firstElementChild as HTMLElement | null;
  const done = () => {
    viewer.close();
    $("viewer-body").replaceChildren();
  };
  if (!media || reducedMotion() || !viewerSource) {
    if (media) media.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 150 }).finished.then(done, done);
    else done();
    return;
  }
  if (media instanceof HTMLVideoElement) media.pause();
  media
    .animate([{ transform: "none", opacity: 1 }, fromThumb(media, viewerSource)], {
      duration: 300,
      easing: "cubic-bezier(0.8, 0, 0.8, 0.2)",
    })
    .finished.then(done, done);
}

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
refreshTexts();
