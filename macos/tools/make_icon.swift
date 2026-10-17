// Draws the MultiBlur app icon and writes Resources/AppIcon.icns.
// Usage: swift tools/make_icon.swift <output.icns> [preview.png]
//        swift tools/make_icon.swift --ios <output.png>   (full-bleed 1024 px square: iOS rounds the corners)
import AppKit
import CoreGraphics

let canvas: CGFloat = 1024

func color(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}

/// A person whose head is a mosaic, inside detection corners, on a blue-to-indigo gradient.
/// Same shapes as scripts/make_icons.py (web and Android icons), in a 512 × 512 top-left-origin design.
func drawIcon(in ctx: CGContext, fullBleed: Bool = false) {
    // macOS icon grid: 824 pt rounded square centered on a 1024 canvas; iOS fills the canvas.
    let tile = fullBleed ? CGRect(x: 0, y: 0, width: 1024, height: 1024) : CGRect(x: 100, y: 100, width: 824, height: 824)
    let tilePath = fullBleed ? CGPath(rect: tile, transform: nil)
        : CGPath(roundedRect: tile, cornerWidth: 185, cornerHeight: 185, transform: nil)

    if !fullBleed {
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -12), blur: 28, color: color(0x000000, 0.35))
        ctx.addPath(tilePath)
        ctx.setFillColor(color(0x4A6BF0))
        ctx.fillPath()
        ctx.restoreGState()
    }

    ctx.saveGState()
    ctx.addPath(tilePath)
    ctx.clip()
    // Design coordinates: 512 units across the tile, y pointing down.
    let unit = tile.width / 512
    ctx.translateBy(x: tile.minX, y: tile.maxY)
    ctx.scaleBy(x: unit, y: -unit)

    let background = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                                colors: [color(0x5B9BFF), color(0x4A3FE0)] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(background, start: .zero, end: CGPoint(x: 512, y: 512),
                           options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])

    // Shoulders.
    let body = CGMutablePath()
    body.move(to: CGPoint(x: 81.2, y: 600))
    body.addLine(to: CGPoint(x: 81.2, y: 469))
    body.addCurve(to: CGPoint(x: 256, y: 331), control1: CGPoint(x: 81.2, y: 349.4), control2: CGPoint(x: 159.9, y: 331))
    body.addCurve(to: CGPoint(x: 430.8, y: 469), control1: CGPoint(x: 352.1, y: 331), control2: CGPoint(x: 430.8, y: 349.4))
    body.addLine(to: CGPoint(x: 430.8, y: 600))
    body.closeSubpath()
    ctx.addPath(body)
    ctx.setFillColor(color(0xFFFFFF, 0.92))
    ctx.fillPath()

    // The head: a mosaic, the app's signature effect.
    let head = CGRect(x: 164, y: 124, width: 184, height: 184)
    ctx.saveGState()
    ctx.addEllipse(in: head)
    ctx.clip()
    let shades: [UInt32] = [0xFFFFFF, 0xDCE3FF, 0xB9C5FF, 0x95A6F5, 0xEEF1FF]
    let cell = head.width / 6
    var seed: UInt32 = 3
    for row in 0..<6 {  // from the bottom
        for col in 0..<6 {
            seed = seed &* 1_103_515_245 &+ 12_345
            ctx.setFillColor(color(shades[Int((seed >> 16) % UInt32(shades.count))]))
            ctx.fill(CGRect(x: head.minX + CGFloat(col) * cell, y: head.minY + CGFloat(5 - row) * cell,
                            width: cell + 0.5, height: cell + 0.5))
        }
    }
    ctx.restoreGState()

    // Viewfinder corners around the face: detection.
    let frame = CGRect(x: 132, y: 92, width: 248, height: 248)
    let arm: CGFloat = 54
    ctx.setStrokeColor(color(0xFFFFFF))
    ctx.setLineWidth(16)
    ctx.setLineCap(.round)
    ctx.setLineJoin(.round)
    for (x, y, dx, dy) in [(frame.minX, frame.minY, 1.0, 1.0), (frame.maxX, frame.minY, -1.0, 1.0),
                           (frame.minX, frame.maxY, 1.0, -1.0), (frame.maxX, frame.maxY, -1.0, -1.0)] {
        ctx.move(to: CGPoint(x: x, y: y + dy * arm))
        ctx.addLine(to: CGPoint(x: x, y: y))
        ctx.addLine(to: CGPoint(x: x + dx * arm, y: y))
    }
    ctx.strokePath()
    ctx.restoreGState()
}

func render(_ size: Int, fullBleed: Bool = false) -> CGImage {
    let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .high
    ctx.scaleBy(x: CGFloat(size) / canvas, y: CGFloat(size) / canvas)
    drawIcon(in: ctx, fullBleed: fullBleed)
    return ctx.makeImage()!
}

func writePNG(_ image: CGImage, to url: URL) {
    let rep = NSBitmapImageRep(cgImage: image)
    try! rep.representation(using: .png, properties: [:])!.write(to: url)
}

let args = CommandLine.arguments
if args.count == 3, args[1] == "--ios" {
    writePNG(render(1024, fullBleed: true), to: URL(fileURLWithPath: args[2]))
    print("iOS icon written to \(args[2])")
    exit(0)
}
guard args.count >= 2 else {
    print("usage: make_icon.swift <output.icns> [preview.png]")
    exit(1)
}
let iconset = FileManager.default.temporaryDirectory.appendingPathComponent("AppIcon-\(UUID().uuidString).iconset")
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
for points in [16, 32, 128, 256, 512] {
    writePNG(render(points), to: iconset.appendingPathComponent("icon_\(points)x\(points).png"))
    writePNG(render(points * 2), to: iconset.appendingPathComponent("icon_\(points)x\(points)@2x.png"))
}
if args.count >= 3 { writePNG(render(1024), to: URL(fileURLWithPath: args[2])) }

let iconutil = Process()
iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", iconset.path, "-o", args[1]]
try iconutil.run()
iconutil.waitUntilExit()
try? FileManager.default.removeItem(at: iconset)
print(iconutil.terminationStatus == 0 ? "Icon written to \(args[1])" : "iconutil failed")
