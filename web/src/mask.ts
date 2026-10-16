import { type Detection, intersection } from "./geometry";

export type MaskMode = "blur" | "mosaic" | "solid" | "image" | "none";

export interface MaskOptions {
  mode: MaskMode;
  mosaicSize: number;
  faceShape: "ellipse" | "box";
  drawScores: boolean;
  /** Stretched over each area in "image" mode; transparent parts show a blur, never the original. */
  replacement: ImageBitmap | null;
}

type Context = OffscreenCanvasRenderingContext2D;

/** The blur is computed on a copy at most this many pixels wide, then stretched back: cheap, and the
 *  area stays unreadable at any size. */
const BLUR_WORK_SIZE = 64;

/** Draws `source` into `ctx` with every detection masked. Patches only sample pixels inside the masked
 *  area of the original frame, so nothing readable leaks back in. */
export class Masker {
  private readonly work = new OffscreenCanvas(1, 1).getContext("2d", { willReadFrequently: true })!;

  render(ctx: Context, source: CanvasImageSource, width: number, height: number, detections: Detection[], options: MaskOptions) {
    ctx.drawImage(source, 0, 0, width, height);
    if (options.mode !== "none") {
      for (const detection of detections) {
        const clipped = intersection(detection.rect, { x: 0, y: 0, width, height });
        if (!clipped) continue;
        // Whole pixels, growing outwards.
        const x = Math.floor(clipped.x), y = Math.floor(clipped.y);
        const roi = { x, y, width: Math.ceil(clipped.x + clipped.width) - x, height: Math.ceil(clipped.y + clipped.height) - y };
        if (roi.width < 2 || roi.height < 2) continue;

        ctx.save();
        ctx.beginPath();
        if (detection.isFace && options.faceShape === "ellipse") {
          // Ellipse inscribed in the (unclipped) detection box, like deface.
          const r = detection.rect;
          ctx.rect(roi.x, roi.y, roi.width, roi.height);
          ctx.clip();
          ctx.beginPath();
          ctx.ellipse(r.x + r.width / 2, r.y + r.height / 2, r.width / 2, r.height / 2, 0, 0, 2 * Math.PI);
        } else {
          ctx.rect(roi.x, roi.y, roi.width, roi.height);
        }
        ctx.clip();
        switch (options.mode) {
          case "solid":
            ctx.fillStyle = "#000";
            ctx.fillRect(roi.x, roi.y, roi.width, roi.height);
            break;
          case "mosaic":
            this.mosaic(ctx, source, roi, Math.max(2, options.mosaicSize));
            break;
          case "image":
            this.blur(ctx, source, roi);
            if (options.replacement) {
              const r = detection.rect;
              ctx.drawImage(options.replacement, r.x, r.y, r.width, r.height);
            }
            break;
          default:
            this.blur(ctx, source, roi);
        }
        ctx.restore();
      }
    }
    if (options.drawScores) {
      for (const detection of detections) drawScore(ctx, detection, width);
    }
  }

  private resizeWork(width: number, height: number) {
    const canvas = this.work.canvas;
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
  }

  /** Gaussian-like blur with a radius of a fifth of the area's size, like the macOS app. */
  private blur(ctx: Context, source: CanvasImageSource, roi: { x: number; y: number; width: number; height: number }) {
    const scale = Math.min(1, BLUR_WORK_SIZE / Math.max(roi.width, roi.height));
    const w = Math.max(1, Math.round(roi.width * scale)), h = Math.max(1, Math.round(roi.height * scale));
    this.resizeWork(w, h);
    this.work.imageSmoothingQuality = "high";
    this.work.drawImage(source, roi.x, roi.y, roi.width, roi.height, 0, 0, w, h);
    const image = this.work.getImageData(0, 0, w, h);
    // Three box blurs approximate a Gaussian of sigma ≈ radius + 0.5.
    const radius = Math.max(1, Math.round(Math.max(w, h) / 5 - 0.5));
    for (let pass = 0; pass < 3; pass++) {
      boxBlur(image.data, w, h, radius, true);
      boxBlur(image.data, w, h, radius, false);
    }
    this.work.putImageData(image, 0, 0);
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = "high";
    ctx.drawImage(this.work.canvas, 0, 0, w, h, roi.x, roi.y, roi.width, roi.height);
  }

  /** Blocks of about `block` pixels (deface's --mosaicsize), stretched to fill the area exactly. */
  private mosaic(ctx: Context, source: CanvasImageSource, roi: { x: number; y: number; width: number; height: number }, block: number) {
    const w = Math.max(1, Math.ceil(roi.width / block)), h = Math.max(1, Math.ceil(roi.height / block));
    this.resizeWork(w, h);
    this.work.imageSmoothingQuality = "high";
    // Only the area itself is sampled.
    this.work.drawImage(source, roi.x, roi.y, roi.width, roi.height, 0, 0, w, h);
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(this.work.canvas, 0, 0, w, h, roi.x, roi.y, roi.width, roi.height);
    ctx.imageSmoothingEnabled = true;
  }
}

/** One horizontal or vertical box-blur pass over RGBA pixels, edges clamped. */
function boxBlur(pixels: Uint8ClampedArray, width: number, height: number, radius: number, horizontal: boolean) {
  const length = horizontal ? width : height, lines = horizontal ? height : width;
  const step = horizontal ? 4 : 4 * width;
  const window = 2 * radius + 1;
  const line = new Float32Array(length * 3);
  for (let l = 0; l < lines; l++) {
    const start = horizontal ? 4 * l * width : 4 * l;
    for (let i = 0; i < length; i++) {
      const p = start + i * step;
      line[3 * i] = pixels[p];
      line[3 * i + 1] = pixels[p + 1];
      line[3 * i + 2] = pixels[p + 2];
    }
    const at = (i: number) => 3 * Math.min(length - 1, Math.max(0, i));
    let r = 0, g = 0, b = 0;
    for (let i = -radius; i <= radius; i++) {
      const j = at(i);
      r += line[j];
      g += line[j + 1];
      b += line[j + 2];
    }
    for (let i = 0; i < length; i++) {
      const p = start + i * step;
      pixels[p] = r / window;
      pixels[p + 1] = g / window;
      pixels[p + 2] = b / window;
      const add = at(i + radius + 1), remove = at(i - radius);
      r += line[add] - line[remove];
      g += line[add + 1] - line[remove + 1];
      b += line[add + 2] - line[remove + 2];
    }
  }
}

/** Green score label above the box, like deface's --draw-scores. */
function drawScore(ctx: Context, detection: Detection, width: number) {
  const r = detection.rect;
  const size = Math.max(12, Math.min(r.height * 0.25, 48));
  ctx.font = `bold ${size}px Helvetica, Arial, sans-serif`;
  ctx.fillStyle = "#00ff00";
  ctx.textBaseline = "bottom";
  const text = detection.score.toFixed(2);
  const x = Math.max(0, Math.min(r.x, width - ctx.measureText(text).width));
  ctx.fillText(text, x, Math.max(size, r.y - 2));
}
