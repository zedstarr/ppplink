// PPP Link for macOS - menu-bar icon: the app's palmtop, drawn as a template image.
// Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.
//
// Idle: faded, flat line on the screen. Ready/negotiating: square wave.
// Connected: solid screen with the wave cut out. Problems: "!" on the screen.

import AppKit

enum MenuBarIcon {
    private static var cache: [LinkKind: NSImage] = [:]

    static func image(for kind: LinkKind) -> NSImage {
        if let img = cache[kind] { return img }
        let img = draw(kind)
        cache[kind] = img
        return img
    }

    private enum Look { case idle, wave, connected, problem }

    private static func lookFor(_ kind: LinkKind) -> Look {
        switch kind {
        case .idle, .stopped, .waiting: return .idle
        case .ready, .negotiating: return .wave
        case .connected: return .connected
        case .notready, .stale, .error: return .problem
        }
    }

    private static func draw(_ kind: LinkKind) -> NSImage {
        let size = NSSize(width: 18, height: 18)
        let look = lookFor(kind)
        // Geometry from the Android icon (x 30..78, y 31..70.5), scaled to 18 pt.
        let k: CGFloat = 0.35
        let ox: CGFloat = (18 - 48 * k) / 2, oy: CGFloat = (18 - 39.5 * k) / 2
        func pt(_ x: CGFloat, _ y: CGFloat) -> NSPoint { NSPoint(x: ox + (x - 30) * k, y: oy + (y - 31) * k) }
        func rect(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat) -> NSRect {
            let a = pt(x0, y0), b = pt(x1, y1)
            return NSRect(x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y)
        }

        let image = NSImage(size: size, flipped: true) { _ in
            let alpha: CGFloat = look == .idle ? 0.55 : 1.0
            NSColor.black.withAlphaComponent(alpha).set()

            // Lid: outline (or solid when connected).
            let lid = NSBezierPath(roundedRect: rect(33, 31, 75, 53), xRadius: 1.2, yRadius: 1.2)
            let screen = NSBezierPath(rect: rect(37, 34.5, 71, 50))
            if look == .connected {
                lid.fill()
            } else {
                let frame = NSBezierPath()
                frame.append(lid)
                frame.append(screen)
                frame.windingRule = .evenOdd
                frame.fill()
            }

            // Base: a solid trapezoid (keys are too small to show at this size).
            let base = NSBezierPath()
            base.move(to: pt(33, 55.5))
            base.line(to: pt(75, 55.5))
            base.line(to: pt(78, 70.5))
            base.line(to: pt(30, 70.5))
            base.close()
            base.fill()

            // Screen contents.
            let line = NSBezierPath()
            line.lineWidth = 1.3
            line.lineCapStyle = .round
            line.lineJoinStyle = .round
            switch look {
            case .idle:
                line.move(to: pt(40, 44)); line.line(to: pt(68, 44))
                line.stroke()
            case .wave, .connected:
                let w: [(CGFloat, CGFloat)] = [(40, 46), (45, 46), (45, 38.5), (51, 38.5), (51, 46),
                                               (57, 46), (57, 38.5), (63, 38.5), (63, 46), (68, 46)]
                line.move(to: pt(w[0].0, w[0].1))
                for p in w.dropFirst() { line.line(to: pt(p.0, p.1)) }
                if look == .connected {
                    NSGraphicsContext.current?.compositingOperation = .destinationOut
                    line.stroke()
                    NSGraphicsContext.current?.compositingOperation = .sourceOver
                } else {
                    line.stroke()
                }
            case .problem:
                line.move(to: pt(54, 37)); line.line(to: pt(54, 44))
                line.stroke()
                let dot = NSBezierPath(ovalIn: NSRect(origin: pt(52.8, 46), size: NSSize(width: 2.4 * k * 1.2, height: 2.4 * k * 1.2)))
                dot.fill()
            }
            return true
        }
        image.isTemplate = true
        return image
    }
}
