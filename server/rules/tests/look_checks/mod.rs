#![allow(dead_code)]
//! Look tooling shared by `tests/looks.rs` and the looks sheet example
//! (docs/VISUALS.md §6): the id hash and the colour mixing. Test code only,
//! never linked into the server.

/// The plate hues as 0xRRGGBB, in `rules::content::HUES` order (VISUALS.md §4.1).
pub const HUE_HEX: [u32; 8] = [
    0x7C9A3C, 0x3DAE93, 0x73808F, 0x93A1B3, 0x7B57BF, 0xB5333C, 0xD9782D, 0xD8CCB2,
];
pub const PAPER: u32 = 0xFBF8F1;
pub const INK: u32 = 0x14171C;

/// FNV-1a over the id's UTF-8 bytes; a hue is `HUES[fnv1a32(id) % 8]`.
pub fn fnv1a32(id: &str) -> u32 {
    id.bytes().fold(0x811C_9DC5, |h, b| {
        (h ^ u32::from(b)).wrapping_mul(0x0100_0193)
    })
}

/// The hue mixed 70% toward `paper`.
pub fn tint(hue: u32) -> u32 {
    mix(hue, PAPER, 70)
}

/// The hue mixed 35% toward `ink`.
pub fn shade(hue: u32) -> u32 {
    mix(hue, INK, 35)
}

/// Per sRGB channel, in integers, an exact half rounding up.
fn mix(a: u32, b: u32, toward_b: u32) -> u32 {
    (0..3).fold(0, |out, i| {
        let (x, y) = ((a >> (8 * i)) & 0xFF, (b >> (8 * i)) & 0xFF);
        out | ((x * (100 - toward_b) + y * toward_b + 50) / 100) << (8 * i)
    })
}
