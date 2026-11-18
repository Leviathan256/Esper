//! The looks sheet and the focus render (docs/VISUALS.md §7, Tier 1).
//!
//! `looks_sheet <content> <out>` writes `<out>/sheet.png`: every shipped unit
//! that has a glyph, as an ink stamp, its 16 px raster enlarged and its token
//! in the three frames, and prints its numbers. `--focus <kind>/<file>` writes
//! `<out>/<kind>-<id>.png`: that unit and the three shipped looks most alike to
//! it, each as a plate, a raster and tokens, and runs every check on it.
//! Exit code 1 when a check fails. The frames mirror `Spec.kt` and the plate
//! recipe of §4.5; the app's own drawing is Tier 2.

#[path = "../tests/look_checks/mod.rs"]
mod look_checks;

use std::collections::BTreeMap;
use std::path::Path;
use std::process::ExitCode;

use look_checks::{HUE_HEX, INK, PAPER, Raster, alike, fnv1a32, path, shade, tint};
use rules::content::{HUES, Look, parse_path};
use tiny_skia::{
    Color, FillRule, LineJoin, Mask, Paint, PathBuilder, Pixmap, Rect, Stroke, Transform,
};

const PAPER_DIM: u32 = 0xEFE9DB;
const ALLY: u32 = 0x1946A6;
const FOE: u32 = 0xA02812;
const NEUTRAL: u32 = 0x48515D;
/// The mat over Fixture Town grass, which a token drawn alone stands on (§4.5).
const MAT: u32 = 0xE9F6E5;
const EMBER: usize = 6;
const CRIMSON: usize = 5;
const BONE: usize = 7;

struct Unit {
    kind: &'static str,
    id: String,
    look: Option<Look>,
}

impl Unit {
    fn glyph(&self) -> Option<&str> {
        self.look.as_ref()?.glyph.as_deref()
    }

    /// The resolved hue (look.schema.md, "Resolving a look"), as an index into `HUES`.
    fn hue(&self) -> usize {
        self.look
            .as_ref()
            .and_then(|l| l.hue.as_deref())
            .map_or((fnv1a32(&self.id) % 8) as usize, |h| {
                HUES.iter().position(|x| *x == h).expect("a validated hue")
            })
    }

    fn no_glyph(&self) -> String {
        format!(
            "no glyph: the app draws its monogram, hue {}",
            HUES[self.hue()]
        )
    }
}

#[derive(Clone, Copy)]
enum Frame {
    Circle,
    Diamond,
    Square,
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let (content, out, focus) = match &args[..] {
        [c, o] => (c, o, None),
        [c, o, flag, f] if flag == "--focus" => (c, o, Some(f.as_str())),
        _ => {
            eprintln!("usage: looks_sheet <content dir> <out dir> [--focus <kind>/<file>.json]");
            return ExitCode::from(2);
        }
    };
    let units = match shipped(Path::new(content)) {
        Ok(u) => u,
        Err(e) => {
            eprintln!("{e}");
            return ExitCode::FAILURE;
        }
    };
    std::fs::create_dir_all(out).expect("create the out dir");
    let ok = match focus {
        None => sheet(&units, Path::new(out)),
        Some(f) => match focus_render(&units, Path::new(content), f, Path::new(out)) {
            Ok(ok) => ok,
            Err(e) => {
                eprintln!("looks: {e}");
                false
            }
        },
    };
    if ok {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

/// Every shipped unit, loaded through the strict loader: monsters, then jobs.
fn shipped(content: &Path) -> Result<Vec<Unit>, String> {
    let read = |kind: &str| -> Result<BTreeMap<String, String>, String> {
        let dir = content.join(kind);
        let mut texts = BTreeMap::new();
        for entry in std::fs::read_dir(&dir).map_err(|e| format!("{}: {e}", dir.display()))? {
            let p = entry.map_err(|e| e.to_string())?.path();
            if p.extension().is_some_and(|x| x == "json") {
                let text = std::fs::read_to_string(&p).map_err(|e| e.to_string())?;
                texts.insert(format!("{kind}/{}", p.file_name().unwrap().display()), text);
            }
        }
        Ok(texts)
    };
    let catalog =
        rules::content::load(&read("jobs")?, &read("monsters")?).map_err(|e| e.to_string())?;
    let monsters = catalog.monsters.into_iter().map(|m| Unit {
        kind: "monsters",
        id: m.id,
        look: m.look,
    });
    let jobs = catalog.jobs.into_iter().map(|j| Unit {
        kind: "jobs",
        id: j.id,
        look: j.look,
    });
    Ok(monsters.chain(jobs).collect())
}

/// The unit's numbers as one line, and whether its own checks pass.
fn numbers(u: &Unit, glyph: &str, units: &[Unit]) -> bool {
    let (segments, subpaths) = parse_path(glyph, true).expect("a validated glyph");
    let r = Raster::of(glyph);
    let (w, h) = r.span();
    let near: Vec<String> = nearest(u, glyph, units)
        .iter()
        .take(3)
        .map(|(o, s)| format!("{} {:.0}%", o.id, s * 100.0))
        .collect();
    println!(
        "{:<12} segments {segments:>2}  subpaths {subpaths}  coverage {:.1}%  survives {:.1}%  box {w}x{h} px  nearest {}",
        u.id,
        r.coverage() * 100.0,
        r.survival() * 100.0,
        near.join(", ")
    );
    let failures = r.failures();
    for f in &failures {
        println!("looks: {}: {f}", u.id);
    }
    failures.is_empty()
}

/// Every other shipped unit with a glyph, most alike first.
fn nearest<'a>(u: &Unit, glyph: &str, units: &'a [Unit]) -> Vec<(&'a Unit, f64)> {
    let mut v: Vec<(&Unit, f64)> = units
        .iter()
        .filter(|o| (o.kind, &o.id) != (u.kind, &u.id))
        .filter_map(|o| Some((o, alike(glyph, o.glyph()?))))
        .collect();
    v.sort_by(|a, b| b.1.total_cmp(&a.1));
    v
}

fn sheet(units: &[Unit], out: &Path) -> bool {
    let drawn: Vec<(&Unit, &str)> = units.iter().filter_map(|u| Some((u, u.glyph()?))).collect();
    let mut ok = true;
    for u in units.iter().filter(|u| u.glyph().is_none()) {
        println!("{:<12} {}", u.id, u.no_glyph());
    }
    // Rows of: stamp 96, raster 96, three 64 px token cells.
    let (row, width) = (112.0, 16.0 + 96.0 + 16.0 + 96.0 + 16.0 + 3.0 * 64.0 + 16.0);
    let mut pm = canvas(width, 16.0 + row * drawn.len().max(1) as f32, PAPER);
    for (i, (u, glyph)) in drawn.iter().enumerate() {
        ok &= numbers(u, glyph, units);
        let y = 16.0 + row * i as f32;
        fill(
            &mut pm,
            glyph,
            INK,
            Transform::from_row(4.0, 0.0, 0.0, 4.0, 16.0, y),
        );
        enlarged(&mut pm, glyph, 128.0, y);
        for (j, frame) in [Frame::Circle, Frame::Diamond, Frame::Square]
            .into_iter()
            .enumerate()
        {
            token(
                &mut pm,
                256.0 + 32.0 + 64.0 * j as f32,
                y + 48.0,
                44.0,
                frame,
                glyph,
            );
        }
    }
    save(&pm, &out.join("sheet.png"));
    ok
}

fn focus_render(units: &[Unit], content: &Path, file: &str, out: &Path) -> Result<bool, String> {
    println!("FOCUS {file}");
    let text = std::fs::read_to_string(content.join(file)).map_err(|e| format!("{file}: {e}"))?;
    let json: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("{file}: {e}"))?;
    let kind = file.split('/').next().unwrap_or_default();
    let id = json["id"].as_str().ok_or(format!("{file}: no id"))?;
    let u = units
        .iter()
        .find(|u| u.kind == kind && u.id == id)
        .ok_or(format!("{file}: not a shipped job or monster"))?;
    let Some(glyph) = u.glyph() else {
        println!("{}", u.no_glyph());
        return Ok(true);
    };
    let mut ok = numbers(u, glyph, units);
    let near = nearest(u, glyph, units);
    for (o, score) in near.iter().filter(|(_, s)| *s > 0.75) {
        ok = false;
        println!(
            "looks: {} is {:.0}% alike to {} (limit 75%). Change the outline, not the details: give it a different top, or cut a face into it.",
            u.id,
            score * 100.0,
            o.id
        );
    }
    let columns: Vec<&Unit> = std::iter::once(u)
        .chain(near.iter().take(3).map(|(o, _)| *o))
        .collect();
    println!(
        "columns: {}",
        columns
            .iter()
            .map(|c| c.id.as_str())
            .collect::<Vec<_>>()
            .join(", ")
    );
    // Each column, top to bottom: plate 156, raster 96, three 44 px tokens,
    // and the tokens at 26 and 16 px on the mat in the unit's own frame.
    let col = 184.0;
    let mut pm = canvas(16.0 + col * columns.len() as f32, 470.0, PAPER);
    for (i, c) in columns.iter().enumerate() {
        let (x, glyph) = (
            16.0 + col * i as f32,
            c.glyph().expect("columns have glyphs"),
        );
        plate(&mut pm, x + 84.0, 16.0 + 84.0, 156.0, c, glyph);
        enlarged(&mut pm, glyph, x + 36.0, 200.0);
        for (j, frame) in [Frame::Circle, Frame::Diamond, Frame::Square]
            .into_iter()
            .enumerate()
        {
            token(
                &mut pm,
                x + 28.0 + 56.0 * j as f32,
                340.0,
                44.0,
                frame,
                glyph,
            );
        }
        let own = if c.kind == "jobs" {
            Frame::Circle
        } else {
            Frame::Diamond
        };
        rect(&mut pm, x, 382.0, 168.0, 72.0, MAT);
        token(&mut pm, x + 56.0, 418.0, 25.8, own, glyph);
        token(&mut pm, x + 112.0, 418.0, 16.0, own, glyph);
    }
    save(&pm, &out.join(format!("{kind}-{id}.png")));
    Ok(ok)
}

/// The 16 px raster the checks see, enlarged 6 times with hard pixels.
fn enlarged(pm: &mut Pixmap, glyph: &str, x: f32, y: f32) {
    rect(pm, x, y, 96.0, 96.0, PAPER_DIM);
    for (i, _) in Raster::of(glyph)
        .0
        .iter()
        .enumerate()
        .filter(|(_, ink)| **ink)
    {
        rect(
            pm,
            x + 6.0 * (i % 16) as f32,
            y + 6.0 * (i / 16) as f32,
            6.0,
            6.0,
            INK,
        );
    }
}

/// A token of size `t` (§4.5, "Token geometry") centred on (cx, cy).
fn token(pm: &mut Pixmap, cx: f32, cy: f32, t: f32, frame: Frame, glyph: &str) {
    let w = (0.075 * t).clamp(1.25, 3.0);
    let at = Transform::from_translate(cx, cy);
    let (outline, side, glyph_box) = match frame {
        Frame::Circle => {
            let p = PathBuilder::from_circle(0.0, 0.0, 0.5 * t - w / 2.0).expect("a circle");
            (p, ALLY, 0.66 * t)
        }
        Frame::Diamond => {
            let p = rounded_square(0.82 * t, 0.09 * t)
                .transform(Transform::from_rotate(45.0))
                .expect("a diamond");
            (p, FOE, 0.60 * t)
        }
        Frame::Square => (rounded_square(0.86 * t, 0.14 * t), NEUTRAL, 0.62 * t),
    };
    pm.fill_path(&outline, &paint(side), FillRule::Winding, at, None);
    let stroke = Stroke {
        width: w,
        ..Stroke::default()
    };
    pm.stroke_path(&outline, &paint(INK), &stroke, at, None);
    let s = glyph_box / 24.0;
    let box_at = Transform::from_row(s, 0.0, 0.0, s, cx - glyph_box / 2.0, cy - glyph_box / 2.0);
    fill(pm, glyph, PAPER, box_at);
}

/// A plate of height `p` (§4.5, "Plates") centred on (cx, cy).
fn plate(pm: &mut Pixmap, cx: f32, cy: f32, p: f32, u: &Unit, glyph: &str) {
    let hue = u.hue();
    let cell = hexagon(p / 2.0);
    let off = p / 32.0;
    pm.fill_path(
        &cell,
        &paint(tint(HUE_HEX[hue])),
        FillRule::Winding,
        Transform::from_translate(cx + off, cy + off),
        None,
    );
    let side = 0.52 * p;
    let s = side / 24.0;
    let box_at = Transform::from_row(s, 0.0, 0.0, s, cx - side / 2.0, cy - side / 2.0);
    let shape = path(glyph);
    fill(pm, glyph, INK, box_at);
    let mut clip = Mask::new(pm.width(), pm.height()).expect("a mask");
    clip.fill_path(&shape, FillRule::EvenOdd, true, box_at);
    let accent = if hue == EMBER || hue == CRIMSON {
        BONE
    } else {
        EMBER
    };
    for d in u.look.iter().flat_map(|l| &l.details) {
        let colour = match d.tone.as_str() {
            "hue" => HUE_HEX[hue],
            "shade" => shade(HUE_HEX[hue]),
            "paper" => PAPER,
            "ink" => INK,
            "accent" => HUE_HEX[accent],
            t => unreachable!("tone {t} passed validation"),
        };
        pm.fill_path(
            &path(&d.d),
            &paint(colour),
            FillRule::EvenOdd,
            box_at,
            Some(&clip),
        );
    }
    let outline = Stroke {
        width: 0.9,
        line_join: LineJoin::Round,
        ..Stroke::default()
    };
    pm.stroke_path(&shape, &paint(INK), &outline, box_at, None);
    let frame = Stroke {
        width: (p / 40.0).max(1.5),
        ..Stroke::default()
    };
    pm.stroke_path(
        &cell,
        &paint(INK),
        &frame,
        Transform::from_translate(cx, cy),
        None,
    );
}

/// A pointy-top hexagon of circumradius `r` around the origin.
fn hexagon(r: f32) -> tiny_skia::Path {
    let mut pb = PathBuilder::new();
    for i in 0..6 {
        let a = (-90.0 + 60.0 * i as f32).to_radians();
        let (x, y) = (r * a.cos(), r * a.sin());
        if i == 0 {
            pb.move_to(x, y)
        } else {
            pb.line_to(x, y)
        }
    }
    pb.close();
    pb.finish().expect("a hexagon")
}

/// A square of side `side` with corners of radius `r`, centred on the origin.
fn rounded_square(side: f32, r: f32) -> tiny_skia::Path {
    let (h, k) = (side / 2.0, 0.5523 * r);
    let mut pb = PathBuilder::new();
    pb.move_to(-h + r, -h);
    pb.line_to(h - r, -h);
    pb.cubic_to(h - r + k, -h, h, -h + r - k, h, -h + r);
    pb.line_to(h, h - r);
    pb.cubic_to(h, h - r + k, h - r + k, h, h - r, h);
    pb.line_to(-h + r, h);
    pb.cubic_to(-h + r - k, h, -h, h - r + k, -h, h - r);
    pb.line_to(-h, -h + r);
    pb.cubic_to(-h, -h + r - k, -h + r - k, -h, -h + r, -h);
    pb.close();
    pb.finish().expect("a rounded square")
}

fn fill(pm: &mut Pixmap, d: &str, colour: u32, at: Transform) {
    pm.fill_path(&path(d), &paint(colour), FillRule::EvenOdd, at, None);
}

fn rect(pm: &mut Pixmap, x: f32, y: f32, w: f32, h: f32, colour: u32) {
    let r = Rect::from_xywh(x, y, w, h).expect("a rect");
    pm.fill_rect(r, &paint(colour), Transform::identity(), None);
}

fn paint(colour: u32) -> Paint<'static> {
    let mut p = Paint::default();
    let [_, r, g, b] = colour.to_be_bytes();
    p.set_color(Color::from_rgba8(r, g, b, 255));
    p
}

fn canvas(w: f32, h: f32, colour: u32) -> Pixmap {
    let mut pm = Pixmap::new(w as u32, h as u32).expect("a canvas");
    let [_, r, g, b] = colour.to_be_bytes();
    pm.fill(Color::from_rgba8(r, g, b, 255));
    pm
}

fn save(pm: &Pixmap, to: &Path) {
    pm.save_png(to).expect("write the picture");
    println!("wrote {}", to.display());
}
