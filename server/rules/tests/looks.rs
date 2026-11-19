//! The look cases the server and the app must agree on, read from
//! `content/fixtures/look/` (content/schema/look.schema.md). Never content.

use std::path::Path;

mod look_checks;

use look_checks::{HUE_HEX, fnv1a32, shade, tint};

use rules::content::{HUES, parse_path};

/// The rows of a fixture table, header dropped, each split on tabs.
fn rows(name: &str) -> Vec<Vec<String>> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../content/fixtures/look")
        .join(name);
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    let rows: Vec<Vec<String>> = text
        .lines()
        .skip(1)
        .map(|l| l.split('\t').map(String::from).collect())
        .collect();
    assert!(!rows.is_empty(), "{name} has no rows");
    rows
}

#[test]
fn the_path_grammar_matches_its_fixture() {
    for row in rows("grammar.tsv") {
        let [path, cut, accept, segments, subpaths] = &row[..] else {
            panic!("grammar.tsv row needs 5 columns: {row:?}")
        };
        let got = parse_path(path, cut == "true");
        if accept == "true" {
            let want = (segments.parse().unwrap(), subpaths.parse().unwrap());
            assert_eq!(got, Ok(want), "{path} (cutCorners {cut})");
        } else {
            assert!(
                got.is_err(),
                "{path} (cutCorners {cut}) accepted as {got:?}"
            );
        }
    }
}

#[test]
fn the_id_hash_picks_the_hue_in_its_fixture() {
    for row in rows("hue.tsv") {
        let [id, hash, hue] = &row[..] else {
            panic!("hue.tsv row needs 3 columns: {row:?}")
        };
        let h = fnv1a32(id);
        assert_eq!(h.to_string(), *hash, "{id}");
        assert_eq!(HUES[(h % 8) as usize], hue, "{id}");
    }
}

#[test]
fn tint_and_shade_match_their_fixture() {
    let rows = rows("mix.tsv");
    assert_eq!(rows.len(), 16);
    for row in rows {
        let [f, hue, hex] = &row[..] else {
            panic!("mix.tsv row needs 3 columns: {row:?}")
        };
        let i = HUES.iter().position(|h| h == hue).expect(hue);
        let mix = match f.as_str() {
            "tint" => tint,
            "shade" => shade,
            _ => panic!("unknown fn {f}"),
        };
        assert_eq!(format!("#{:06X}", mix(HUE_HEX[i])), *hex, "{f}({hue})");
    }
}

// The check code on the fixed cases of docs/VISUALS.md §7.

const FX_ARROW: &str = "M12 2 L22 21 L12 15.5 L2 21 Z";
const FX_RING: &str = "M12 2 C17.52 2 22 6.48 22 12 C22 17.52 17.52 22 12 22 C6.48 22 2 17.52 2 12 C2 6.48 6.48 2 12 2 Z M12 7 C14.76 7 17 9.24 17 12 C17 14.76 14.76 17 12 17 C9.24 17 7 14.76 7 12 C7 9.24 9.24 7 12 7 Z";

#[test]
fn a_moved_and_shrunk_copy_is_alike() {
    let copy = "M14 6 L22 21.2 L14 16.8 L6 21.2 Z";
    let score = look_checks::alike(FX_ARROW, copy);
    assert!(score > 0.75, "{score}");
}

#[test]
fn a_mirror_is_alike() {
    let flag = "M5 2 L9 2 L9 4 L20 8 L9 12 L9 22 L5 22 Z";
    let mirror = "M19 2 L15 2 L15 4 L4 8 L15 12 L15 22 L19 22 Z";
    let score = look_checks::alike(flag, mirror);
    assert!(score > 0.75, "{score}");
}

#[test]
fn an_arrow_is_not_a_ring() {
    let score = look_checks::alike(FX_ARROW, FX_RING);
    assert!(score < 0.75, "{score}");
}

#[test]
fn a_cross_of_thin_bars_fails_thin_parts() {
    let cross = "M10.5 3 L13.5 3 L13.5 10.5 L21 10.5 L21 13.5 L13.5 13.5 L13.5 21 L10.5 21 L10.5 13.5 L3 13.5 L3 10.5 L10.5 10.5 Z";
    let failures = look_checks::Raster::of(cross).failures();
    assert!(
        failures.iter().any(|f| f.starts_with("too thin")),
        "{failures:?}"
    );
}
