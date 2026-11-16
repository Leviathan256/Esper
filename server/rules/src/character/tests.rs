//! Mirrors the Kotlin `GoldenSaveFixturesTest` and `CharacterRulesTest` case
//! for case, against the shipped content/ (squire and apprentice start; knight
//! needs squire).

use super::save_codec::{decode, encode};
use super::*;
use crate::content::tests::shipped;
use std::path::Path;

fn fixture_dir() -> std::path::PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../content/fixtures/save")
}

// --- GoldenSaveFixturesTest ---

#[test]
fn there_is_one_fixture_per_save_schema_version() {
    for v in 0..=CURRENT_SCHEMA_VERSION {
        for name in [format!("v{v}.json"), format!("v{v}.expected.json")] {
            assert!(fixture_dir().join(&name).is_file(), "missing {name}");
        }
    }
}

#[test]
fn every_fixture_decodes_migrates_and_re_encodes_to_its_expected_json() {
    let mut checked = 0;
    for entry in std::fs::read_dir(fixture_dir()).unwrap() {
        let path = entry.unwrap().path();
        let name = path.file_name().unwrap().to_str().unwrap().to_owned();
        let Some(v) = name.strip_prefix('v').and_then(|n| n.strip_suffix(".json")) else {
            continue;
        };
        if !v.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let input = std::fs::read_to_string(&path).unwrap();
        let expected =
            std::fs::read_to_string(fixture_dir().join(format!("v{v}.expected.json"))).unwrap();
        assert_eq!(
            encode(&decode(&input).unwrap()),
            expected.trim_end(),
            "{name}"
        );
        checked += 1;
    }
    assert!(
        checked > CURRENT_SCHEMA_VERSION as usize,
        "{checked} fixtures"
    );
}

#[test]
fn decode_fills_kotlin_defaults_and_ignores_unknown_keys() {
    let s = decode(
        r#"{"name":"A","baseScores":{"str":1,"dex":2,"con":3,"int":4,"wis":5,"cha":6},
            "currentJobId":"squire","bestiary":{"wolf":{"monsterId":"wolf"}},"later":true}"#,
    )
    .unwrap();
    assert_eq!(
        encode(&s),
        r#"{"schemaVersion":1,"name":"A","baseScores":{"str":1,"dex":2,"con":3,"int":4,"wis":5,"cha":6},"currentJobId":"squire","level":1,"xp":0,"jobPoints":{},"unlockedJobIds":[],"learnedAbilityIds":[],"bestiary":{"wolf":{"monsterId":"wolf","timesDefeated":0}}}"#
    );
}

#[test]
fn migrate_rejects_a_non_object_or_a_non_integer_version() {
    for bad in [
        "[]",
        "1",
        r#"{"schemaVersion":"x"}"#,
        r#"{"schemaVersion":1.5}"#,
    ] {
        assert!(decode(bad).unwrap_err().is_data(), "{bad}");
    }
    assert!(decode("{not json").unwrap_err().is_syntax());
}

// --- CharacterRulesTest ---

fn squire() -> CharacterState {
    create(&shipped(), "  Ramza ", "squire").unwrap()
}

fn code(r: Result<CharacterState, Rejection>) -> &'static str {
    r.unwrap_err().code
}

fn put(text: &str, stored: Option<&CharacterState>) -> Result<CharacterState, Rejection> {
    update(&shipped(), text, stored)
}

fn put_state(s: &CharacterState, stored: Option<&CharacterState>) -> &'static str {
    code(put(&encode(s), stored))
}

#[test]
fn create_trims_the_name_and_starts_at_the_chosen_job() {
    let s = squire();
    assert_eq!(
        (s.name.as_str(), s.current_job_id.as_str(), s.level),
        ("Ramza", "squire", 1)
    );
    assert_eq!(s.base_scores, STARTING_SCORES);
}

#[test]
fn create_rejects_blank_too_long_and_non_starting_job() {
    let c = shipped();
    assert_eq!(code(create(&c, "   ", "squire")), "invalid_name");
    assert_eq!(code(create(&c, &"a".repeat(25), "squire")), "invalid_name");
    assert!(create(&c, &"a".repeat(24), "squire").is_ok());
    assert_eq!(code(create(&c, "Ramza", "knight")), "invalid_job");
    assert_eq!(code(create(&c, "Ramza", "nope")), "invalid_job");
}

#[test]
fn update_accepts_a_valid_round_trip_and_a_level_up() {
    let s = squire();
    assert_eq!(put(&encode(&s), Some(&s)), Ok(s.clone()));
    let mut up = s.clone();
    up.level = 2;
    up.unlocked_job_ids = ["knight".to_owned()].into();
    up.job_points = [("squire".to_owned(), 10)].into();
    assert_eq!(put(&encode(&up), Some(&s)), Ok(up.clone()));
    // No stored row: the one-time import of a pre-server local save.
    assert_eq!(put(&encode(&up), None), Ok(up));
}

#[test]
fn update_rejects_rename_lower_level_unknown_ids_wrong_schema_bad_json() {
    let mut stored = squire();
    stored.level = 3;
    let with = |f: &dyn Fn(&mut CharacterState)| {
        let mut s = stored.clone();
        f(&mut s);
        s
    };
    let s = Some(&stored);
    assert_eq!(
        put_state(&with(&|s| s.name = "Delita".into()), s),
        "invalid_state"
    );
    assert_eq!(put_state(&with(&|s| s.level = 2), s), "invalid_state");
    assert_eq!(
        put_state(&with(&|s| s.current_job_id = "nope".into()), s),
        "invalid_job"
    );
    let nope = || "nope".to_owned();
    assert_eq!(
        put_state(&with(&|s| s.unlocked_job_ids = [nope()].into()), s),
        "invalid_job"
    );
    assert_eq!(
        put_state(&with(&|s| s.job_points = [(nope(), 1)].into()), s),
        "invalid_job"
    );
    assert_eq!(
        put_state(&with(&|s| s.learned_ability_ids = [nope()].into()), s),
        "invalid_job"
    );
    let future = with(&|s| s.schema_version = CURRENT_SCHEMA_VERSION + 1);
    assert_eq!(put_state(&future, s), "invalid_state");
    assert_eq!(code(put(r#"{"name":"x"}"#, s)), "invalid_state");
    assert_eq!(code(put("{not json", s)), "bad_request");
}

#[test]
fn import_without_a_stored_row_still_checks_the_name() {
    let mut s = squire();
    s.name = "a".repeat(25);
    assert_eq!(put_state(&s, None), "invalid_state");
    s.name = " ".into();
    assert_eq!(put_state(&s, None), "invalid_state");
}
