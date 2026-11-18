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
