import { type Detection, type Found, type Rect, enlarge, nonMaximumSuppression, suppressDuplicates, tiles } from "./geometry";
import { type Models, ort } from "./models";

export interface DetectOptions {
  plates: boolean;
  faces: boolean;
  plateConfidence: number;
  faceThreshold: number;
  plateMaskScale: number;
  faceMaskScale: number;
  /** Faces are analyzed at up to this many pixels per side (deface's --scale). */
  faceMaxSide: number;
  /** Extra passes on overlapping tiles to find small or distant objects. */
  tiled: boolean;
}

export interface DetectionResult {
  detections: Detection[];
  plates: number;
  faces: number;
}

const PLATE_INPUT = 640;
const PLATE_TILE = 960;
const PLATE_IOU = 0.45;
const FACE_NMS = 0.3;
/** Faces are never analyzed above this size, like the CoreML model of the macOS app. */
export const FACE_MAX_SIDE = 2048;

type Context = OffscreenCanvasRenderingContext2D;

function context(width: number, height: number): Context {
  return new OffscreenCanvas(width, height).getContext("2d", { willReadFrequently: true })!;
}

/** Finds license plates (YOLOv11) and faces (CenterFace from deface) in a frame. */
export class Detector {
  private readonly plateContext = context(PLATE_INPUT, PLATE_INPUT);
  private faceContext = context(32, 32);

  constructor(private readonly models: Models) {}

  async detect(source: CanvasImageSource, width: number, height: number, options: DetectOptions): Promise<DetectionResult> {
    const frame: Rect = { x: 0, y: 0, width, height };
    const plates = options.plates
      ? await this.search(frame, options.tiled ? PLATE_TILE : null, (region) => this.detectPlates(source, region, options.plateConfidence))
      : [];
    // Faces are already analyzed at up to 2048 px: tiles only help larger images at full resolution.
    const faceTile = options.tiled && options.faceMaxSide >= FACE_MAX_SIDE ? FACE_MAX_SIDE : null;
    const faces = options.faces
      ? await this.search(frame, faceTile, (region) => this.detectFaces(source, region, options.faceThreshold, options.faceMaxSide))
      : [];
    return {
      detections: [
        ...plates.map((f) => ({ rect: enlarge(f.rect, options.plateMaskScale), score: f.score, isFace: false, core: f.rect })),
        ...faces.map((f) => ({ rect: enlarge(f.rect, options.faceMaskScale), score: f.score, isFace: true, core: f.rect })),
      ],
      plates: plates.length,
      faces: faces.length,
    };
  }

  /** Runs `detect` on the whole frame and, when `tileSize` is set and the frame is larger, on overlapping
   *  tiles too; duplicates found in several passes are merged. */
  private async search(frame: Rect, tileSize: number | null, detect: (region: Rect) => Promise<Found[]>): Promise<Found[]> {
    const found = await detect(frame);
    if (!tileSize) return found;
    for (const tile of tiles(frame.width, frame.height, tileSize)) found.push(...(await detect(tile)));
    return suppressDuplicates(found);
  }

  /** YOLO on a letterboxed 640 × 640 copy of `region`; boxes in frame pixels. */
  private async detectPlates(source: CanvasImageSource, region: Rect, confidence: number): Promise<Found[]> {
    const size = PLATE_INPUT;
    const scale = size / Math.max(region.width, region.height);
    const drawnWidth = region.width * scale, drawnHeight = region.height * scale;
    const padX = (size - drawnWidth) / 2, padY = (size - drawnHeight) / 2;
    const ctx = this.plateContext;
    ctx.fillStyle = "rgb(114, 114, 114)"; // Ultralytics' letterbox color
    ctx.fillRect(0, 0, size, size);
    ctx.drawImage(source, region.x, region.y, region.width, region.height, padX, padY, drawnWidth, drawnHeight);
    const input = planarRGB(ctx.getImageData(0, 0, size, size).data, size * size, 1 / 255);

    const session = this.models.plates;
    const outputs = await session.run({ [session.inputNames[0]]: new ort.Tensor("float32", input, [1, 3, size, size]) });
    const output = outputs[session.outputNames[0]];
    const data = output.data as Float32Array;
    const count = output.dims[2];
    const found: Found[] = [];
    for (let i = 0; i < count; i++) {
      const score = data[4 * count + i];
      if (score < confidence) continue;
      const cx = data[i], cy = data[count + i], w = data[2 * count + i], h = data[3 * count + i];
      found.push({
        rect: {
          x: region.x + (cx - w / 2 - padX) / scale,
          y: region.y + (cy - h / 2 - padY) / scale,
          width: w / scale,
          height: h / scale,
        },
        score,
      });
    }
    output.dispose();
    return nonMaximumSuppression(found, PLATE_IOU);
  }

  /** CenterFace on `region`, resized so both sides are multiples of 32 like deface. Port of FaceDetector.swift. */
  private async detectFaces(source: CanvasImageSource, region: Rect, threshold: number, maxSide: number): Promise<Found[]> {
    const fit = Math.min(1, maxSide / Math.max(region.width, region.height));
    const width = Math.ceil((region.width * fit) / 32) * 32;
    const height = Math.ceil((region.height * fit) / 32) * 32;
    const scaleX = width / region.width, scaleY = height / region.height;
    if (this.faceContext.canvas.width !== width || this.faceContext.canvas.height !== height) {
      this.faceContext = context(width, height);
    }
    const ctx = this.faceContext;
    ctx.drawImage(source, region.x, region.y, region.width, region.height, 0, 0, width, height);
    const input = planarRGB(ctx.getImageData(0, 0, width, height).data, width * height, 1);

    const session = this.models.faces;
    const outputs = await session.run({ [session.inputNames[0]]: new ort.Tensor("float32", input, [1, 3, height, width]) });
    const [heatmap, scale, offset] = session.outputNames.slice(0, 3).map((name) => outputs[name]);
    const rows = heatmap.dims[2], cols = heatmap.dims[3], plane = rows * cols;
    const heat = heatmap.data as Float32Array, scales = scale.data as Float32Array, offsets = offset.data as Float32Array;

    const found: Found[] = [];
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const i = r * cols + c;
        const score = heat[i];
        if (score <= threshold) continue;
        const s0 = Math.exp(scales[i]) * 4, s1 = Math.exp(scales[plane + i]) * 4;
        const o0 = offsets[i], o1 = offsets[plane + i];
        const x1 = Math.min(Math.max(0, (c + o1 + 0.5) * 4 - s1 / 2), width);
        const y1 = Math.min(Math.max(0, (r + o0 + 0.5) * 4 - s0 / 2), height);
        const x2 = Math.min(x1 + s1, width), y2 = Math.min(y1 + s0, height);
        found.push({
          rect: { x: region.x + x1 / scaleX, y: region.y + y1 / scaleY, width: (x2 - x1) / scaleX, height: (y2 - y1) / scaleY },
          score,
        });
      }
    }
    for (const name of session.outputNames) outputs[name].dispose();
    return nonMaximumSuppression(found, FACE_NMS);
  }
}

/** RGBA pixels → planar RGB floats ([R…, G…, B…]), multiplied by `factor`. */
function planarRGB(pixels: Uint8ClampedArray, count: number, factor: number): Float32Array {
  const out = new Float32Array(3 * count);
  for (let i = 0, p = 0; i < count; i++, p += 4) {
    out[i] = pixels[p] * factor;
    out[count + i] = pixels[p + 1] * factor;
    out[2 * count + i] = pixels[p + 2] * factor;
  }
  return out;
}
