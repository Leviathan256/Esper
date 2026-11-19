#![allow(dead_code)]
//! Look tooling shared by `tests/looks.rs` and the looks sheet example
//! (docs/VISUALS.md §6): the rasteriser, the pixel checks of §7, the id hash
//! and the colour mixing. Test code only, never linked into the server.

use tiny_skia::{FillRule, Mask, Path, PathBuilder, Transform};

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

/// A look path (look.schema.md grammar, already accepted by
/// `rules::content::parse_path`) in its 24-unit box.
pub fn path(d: &str) -> Path {
    let mut numbers = d.split(' ').filter(|t| *t != "Z").map(|t| {
        t.trim_start_matches(['M', 'L', 'C'])
            .parse::<f32>()
            .expect("a number")
    });
    let mut n = || numbers.next().expect("a number");
    let mut pb = PathBuilder::new();
    for t in d.split(' ') {
        match t.as_bytes()[0] {
            b'M' => pb.move_to(n(), n()),
            b'L' => pb.line_to(n(), n()),
            b'C' => pb.cubic_to(n(), n(), n(), n(), n(), n()),
            b'Z' => pb.close(),
            _ => {}
        }
    }
    pb.finish().expect("a non-empty path")
}

/// Ink pixels of `d` drawn into a `size` x `size` raster with `transform`:
/// anti-aliased, even-odd, ink where alpha is 128 or more.
pub fn ink(d: &str, size: u32, transform: Transform) -> Vec<bool> {
    let mut mask = Mask::new(size, size).expect("a raster size");
    mask.fill_path(&path(d), FillRule::EvenOdd, true, transform);
    mask.data().iter().map(|&a| a >= 128).collect()
}

/// A glyph rasterised as the checks see it: 16 x 16 px, the box filling it.
pub struct Raster(pub Vec<bool>);

impl Raster {
    pub const SIZE: usize = 16;

    pub fn of(glyph: &str) -> Raster {
        Raster(ink(
            glyph,
            16,
            Transform::from_scale(16.0 / 24.0, 16.0 / 24.0),
        ))
    }

    fn at(&self, x: i32, y: i32) -> bool {
        let n = Self::SIZE as i32;
        (0..n).contains(&x) && (0..n).contains(&y) && self.0[(y * n + x) as usize]
    }

    fn count(&self) -> usize {
        self.0.iter().filter(|&&i| i).count()
    }

    /// Ink pixels over all 256.
    pub fn coverage(&self) -> f64 {
        self.count() as f64 / 256.0
    }

    /// Of the ink pixels, the share whose four edge neighbours are ink too.
    pub fn survival(&self) -> f64 {
        let n = Self::SIZE as i32;
        let kept = (0..n * n)
            .map(|i| (i % n, i / n))
            .filter(|&(x, y)| {
                self.at(x, y)
                    && self.at(x - 1, y)
                    && self.at(x + 1, y)
                    && self.at(x, y - 1)
                    && self.at(x, y + 1)
            })
            .count();
        kept as f64 / self.count().max(1) as f64
    }

    /// The ink's bounding box in pixels, width and height.
    pub fn span(&self) -> (usize, usize) {
        let n = Self::SIZE;
        let (xs, ys): (Vec<usize>, Vec<usize>) = (0..n * n)
            .filter(|&i| self.0[i])
            .map(|i| (i % n, i / n))
            .unzip();
        let w = |v: &[usize]| {
            v.iter()
                .max()
                .map_or(0, |m| m + 1 - v.iter().min().unwrap())
        };
        (w(&xs), w(&ys))
    }

    /// The messages of the checks this glyph fails on its own (§7).
    pub fn failures(&self) -> Vec<String> {
        let mut out = Vec::new();
        let c = self.coverage();
        if c < 0.22 {
            out.push("too little ink at 16 px: thicken it".into());
        }
        if c > 0.62 {
            out.push("too much ink at 16 px: cut holes or slim it".into());
        }
        if self.survival() < 0.35 {
            out.push("too thin: under 35% of the ink survives a one-pixel erosion. A pixel is 1.5 units: make the body 6 units or more across".into());
        }
        let (w, h) = self.span();
        if w < 10 || h < 10 {
            out.push("too small in its box: span at least 10 of the 16 px both ways".into());
        }
        out
    }
}

/// How alike two glyphs are, 0 to 1 (§7): the best intersection over union
/// of their 16 px rasters as drawn, and of the two aligned, either way round.
pub fn alike(a: &str, b: &str) -> f64 {
    let (aligned_a, aligned_b) = (aligned(a), aligned(b));
    let mirrored: Vec<bool> = (0..32 * 32)
        .map(|i| aligned_b[i / 32 * 32 + 31 - i % 32])
        .collect();
    iou(&Raster::of(a).0, &Raster::of(b).0)
        .max(iou(&aligned_a, &aligned_b))
        .max(iou(&aligned_a, &mirrored))
}

/// The glyph in 32 px with its ink centroid at the centre and its ink a
/// quarter of the raster, measured at 96 px; so a move or resize changes nothing.
fn aligned(d: &str) -> Vec<bool> {
    let big = ink(d, 96, Transform::from_scale(4.0, 4.0));
    let at: Vec<(f32, f32)> = (0..96 * 96)
        .filter(|&i| big[i])
        .map(|i| ((i % 96) as f32 + 0.5, (i / 96) as f32 + 0.5))
        .collect();
    let n = at.len().max(1) as f32;
    let (cx, cy) = at
        .iter()
        .fold((0.0, 0.0), |(x, y), p| (x + p.0 / n, y + p.1 / n));
    let k = (2304.0 / n).sqrt() / 3.0;
    ink(
        d,
        32,
        Transform::from_row(4.0 * k, 0.0, 0.0, 4.0 * k, 16.0 - cx * k, 16.0 - cy * k),
    )
}

fn iou(a: &[bool], b: &[bool]) -> f64 {
    let both = a.iter().zip(b).filter(|(x, y)| **x && **y).count();
    let either = a.iter().zip(b).filter(|(x, y)| **x || **y).count();
    both as f64 / either.max(1) as f64
}
