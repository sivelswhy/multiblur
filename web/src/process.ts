import {
  ALL_FORMATS,
  BlobSource,
  BufferTarget,
  Conversion,
  Input,
  Mp4OutputFormat,
  Output,
  QUALITY_HIGH,
  type VideoSample,
} from "mediabunny";
import { type DetectOptions, Detector, FACE_MAX_SIDE } from "./detector";
import { type MaskOptions, Masker } from "./mask";
import type { Options } from "./options";
import { CutDetector, Tracker } from "./tracker";

export interface Result {
  blob: Blob;
  name: string;
  plates: number;
  faces: number;
  /** Something the user should know about the output, e.g. the sound had to be removed. */
  warning?: string;
}

export interface Callbacks {
  progress: (fraction: number) => void;
  /** Receives the processed frame from time to time, to show a live preview. */
  preview: (frame: OffscreenCanvas) => void;
  /** Lets the caller stop a video; images are too quick to need it. */
  onCancel: (cancel: () => void) => void;
}

/** Video: how long a tracked object stays masked after the detector last saw it. */
const TRACK_MEMORY = 0.4;
const PREVIEW_INTERVAL = 150;

export const isVideo = (file: File) => file.type.startsWith("video/") || /\.(mp4|mov|m4v|webm|mkv)$/i.test(file.name);

export function outputName(file: File, extension: string): string {
  const stem = file.name.replace(/\.[^.]+$/, "");
  return `${stem}_anonymized.${extension}`;
}

function detectOptions(options: Options, tiled: boolean): DetectOptions {
  return {
    plates: options.maskPlates,
    faces: options.maskFaces,
    plateConfidence: options.plateConfidence,
    faceThreshold: options.faceThreshold,
    plateMaskScale: options.plateMaskScale,
    faceMaskScale: options.faceMaskScale,
    faceMaxSide: options.faceResolution || FACE_MAX_SIDE,
    tiled,
  };
}

function maskOptions(options: Options, replacement: ImageBitmap | null): MaskOptions {
  return { mode: options.mode, mosaicSize: options.mosaicSize, faceShape: options.faceShape, drawScores: options.drawScores, replacement };
}

/** Decodes an image upright (EXIF orientation applied). */
export async function decodeImage(file: Blob): Promise<ImageBitmap> {
  try {
    return await createImageBitmap(file, { imageOrientation: "from-image" });
  } catch {
    // Some formats (e.g. SVG, or HEIC in Safari) only decode through an <img>.
    const url = URL.createObjectURL(file);
    try {
      const img = new Image();
      img.src = url;
      await img.decode();
      return await createImageBitmap(img);
    } finally {
      URL.revokeObjectURL(url);
    }
  }
}

export async function processImage(
  file: File, detector: Detector, options: Options, replacement: ImageBitmap | null, callbacks: Callbacks,
): Promise<Result> {
  const image = await decodeImage(file);
  const { width, height } = image;
  const canvas = new OffscreenCanvas(width, height);
  const ctx = canvas.getContext("2d")!;
  const found = await detector.detect(image, width, height, detectOptions(options, options.smallObjects !== "off"));
  new Masker().render(ctx, image, width, height, found.detections, maskOptions(options, replacement));
  image.close();
  callbacks.preview(canvas);

  // Same format as the original when the browser can write it; the canvas never carries over EXIF or
  // GPS metadata.
  const wanted = ["image/jpeg", "image/png", "image/webp"].includes(file.type) ? file.type : "image/png";
  const blob = await canvas.convertToBlob({ type: wanted, quality: 0.92 });
  const extension = { "image/jpeg": "jpg", "image/webp": "webp" }[blob.type] ?? "png";
  callbacks.progress(1);
  return { blob, name: outputName(file, extension), plates: found.plates, faces: found.faces };
}

export async function processVideo(
  file: File, detector: Detector, options: Options, replacement: ImageBitmap | null, callbacks: Callbacks,
): Promise<Result> {
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
  const track = await input.getPrimaryVideoTrack();
  if (!track) throw new Error("No video track found.");
  const frameRate = (await track.computePacketStats(100)).averagePacketRate || 30;

  const tracker = new Tracker(Math.max(3, Math.round(frameRate * TRACK_MEMORY)));
  const cuts = new CutDetector();
  const masker = new Masker();
  const detect = detectOptions(options, options.smallObjects === "all");
  const mask = maskOptions(options, replacement);
  const plateIDs = new Set<number>(), faceIDs = new Set<number>();
  let frame: OffscreenCanvas | null = null, output: OffscreenCanvas | null = null;
  let lastPreview = 0;

  const process = async (sample: VideoSample) => {
    const width = sample.displayWidth, height = sample.displayHeight;
    if (!frame || frame.width !== width || frame.height !== height) {
      frame = new OffscreenCanvas(width, height);
      output = new OffscreenCanvas(width, height);
    }
    const frameContext = frame.getContext("2d")!;
    sample.draw(frameContext, 0, 0, width, height);

    const found = await detector.detect(frame, width, height, detect);
    if (cuts.isCut(frame)) tracker.reset();
    const tracked = tracker.update(found.detections);
    // Objects are counted once they've been actually detected in a frame.
    for (const d of tracked) if (found.detections.some((f) => f.core === d.core)) (d.isFace ? faceIDs : plateIDs).add(d.id!);

    masker.render(output!.getContext("2d")!, frame, width, height, tracked, mask);
    if (performance.now() - lastPreview > PREVIEW_INTERVAL) {
      lastPreview = performance.now();
      callbacks.preview(output!);
    }
    return output!;
  };

  const target = new BufferTarget();
  const conversion = await Conversion.init({
    input,
    output: new Output({ format: new Mp4OutputFormat({ fastStart: "in-memory" }), target }),
    tracks: "primary",
    video: {
      // Frames are rotated upright before detection, and written upright.
      allowTransformationMetadata: false,
      quality: QUALITY_HIGH,
      process,
    },
    audio: { discard: !options.keepAudio },
  });
  if (!conversion.isValid) {
    const reasons = conversion.discardedTracks.map((t) => `${t.track.type}: ${t.reason}`).join(", ");
    throw new Error(`This browser can't convert this video (${reasons}).`);
  }
  const droppedAudio = options.keepAudio && conversion.discardedTracks.some((t) => t.track.type === "audio");
  conversion.onProgress = (fraction) => callbacks.progress(fraction);
  callbacks.onCancel(() => void conversion.cancel());
  await conversion.execute();

  const blob = new Blob([target.buffer!], { type: "video/mp4" });
  return {
    blob,
    name: outputName(file, "mp4"),
    plates: plateIDs.size,
    faces: faceIDs.size,
    warning: droppedAudio ? "This browser can't encode the sound of this video: it was removed." : undefined,
  };
}
