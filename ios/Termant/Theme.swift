import SwiftUI

/// The colours of the Android app, dark and light.
struct Palette {
    let bg, surface, surfaceHigh, text, muted, accent, onAccent, error: Color
}

enum Theme {
    static func color(_ hex: UInt32) -> Color {
        Color(red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255, blue: Double(hex & 0xff) / 255)
    }

    static let dark = Palette(bg: color(0x0d1117), surface: color(0x161b22), surfaceHigh: color(0x21262d),
                              text: color(0xe6edf3), muted: color(0x8b949e), accent: color(0xff7a45),
                              onAccent: color(0x1a0f08), error: color(0xff7b72))
    static let light = Palette(bg: color(0xf6f8fa), surface: color(0xffffff), surfaceHigh: color(0xeaeef2),
                               text: color(0x24292f), muted: color(0x57606a), accent: color(0xd25a2e),
                               onAccent: color(0xffffff), error: color(0xcf222e))

    static func palette(_ theme: String) -> Palette {
        theme == "light" ? light : dark
    }

    /// **bold** and `code` as models write them; everything else as it is.
    static func rich(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
    }
}
