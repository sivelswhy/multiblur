"""Exports the plate (YOLO) and face (deface's CenterFace) models to ONNX for the web app.

Outputs, in web/public/models/:
  - plates.onnx: YOLOv11 plate detector, 640 × 640 input, raw [1, 5, 8400] output (NMS runs in JS).
  - centerface.onnx: deface's CenterFace with dynamic height and width (multiples of 32), weights
    stored as initializers only, so ONNX Runtime Web can fold them.

Usage: .venv/bin/python scripts/export_onnx.py [size]   (plate model size, default "s")
"""
import os
import shutil
import sys

os.environ.setdefault("YOLO_OFFLINE", "1")

import deface
import onnx
from onnx.tools.update_model_dims import update_inputs_outputs_dims

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT_DIR = os.path.join(ROOT, "web", "public", "models")
SIZE = sys.argv[1] if len(sys.argv) > 1 else "s"


def export_plates() -> None:
    from ultralytics import YOLO

    weights = os.path.join(ROOT, "models", f"license-plate-finetune-v1{SIZE}.pt")
    if not os.path.exists(weights):
        sys.exit(f"Missing {weights}: run `.venv/bin/python multiblur.py --download {SIZE}` first.")
    exported = YOLO(weights).export(format="onnx", imgsz=640, opset=17, simplify=True, dynamic=False)
    shutil.move(exported, os.path.join(OUT_DIR, "plates.onnx"))


def export_faces() -> None:
    model = onnx.load(os.path.join(os.path.dirname(deface.__file__), "centerface.onnx"))
    # Old exporters list every weight as a graph input too; keep only the image.
    initializers = {i.name for i in model.graph.initializer}
    for graph_input in [i for i in model.graph.input if i.name in initializers]:
        model.graph.input.remove(graph_input)
    used = {name for node in model.graph.node for name in node.input}
    for unused in [i for i in model.graph.initializer if i.name not in used]:
        model.graph.initializer.remove(unused)
    # Same dynamic shapes as deface's onnxrt backend.
    model = update_inputs_outputs_dims(
        model,
        {"input.1": ["B", 3, "H", "W"]},
        {"537": ["B", 1, "h", "w"], "538": ["B", 2, "h", "w"], "539": ["B", 2, "h", "w"], "540": ["B", 10, "h", "w"]},
    )
    onnx.checker.check_model(model)
    onnx.save(model, os.path.join(OUT_DIR, "centerface.onnx"))


if __name__ == "__main__":
    os.makedirs(OUT_DIR, exist_ok=True)
    export_plates()
    export_faces()
    for name in sorted(os.listdir(OUT_DIR)):
        print(f"{name}: {os.path.getsize(os.path.join(OUT_DIR, name)) / 1e6:.1f} MB")
