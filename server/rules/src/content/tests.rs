//! Mirrors the Kotlin ShippedContentTest, ContentValidatorTest and
//! ContentLoaderTest case for case, plus the unknown-field rejection that only
//! the Rust loader has.

use super::*;
use std::path::Path;

const SQUIRE: &str = r#"{
  "schemaVersion": 1,
  "id": "squire",
  "displayName": "Squire",
  "description": "A front-line trainee.",
  "hitDie": "1d10",
  "baseArmorClass": 12,
  "attackAbility": "str",
  "damage": "1d8",
  "speed": 8,
  "moveRangeCells": 4,
  "attackRangeCells": 1,
  "statGrowth": { "str": 1, "con": 1 },
  "abilitiesGranted": ["shield_bash"],
  "prerequisites": [],
  "unlocks": [],
  "jobPointsToMaster": 100
}"#;

const GOBLIN: &str = r#"{
  "schemaVersion": 1,
  "id": "goblin",
  "displayName": "Goblin",
  "maxHp": 7,
  "armorClass": 13,
  "attackBonus": 4,
  "damage": "1d6+2",
  "speed": 7,
  "moveRangeCells": 3,
  "attackRangeCells": 1,
  "xpReward": 50,
  "jobPointsReward": 2,
  "bestiaryText": "A vicious but cowardly raider."
}"#;

fn job() -> Job {
    serde_json::from_str(SQUIRE).unwrap()
}

fn monster() -> Monster {
    serde_json::from_str(GOBLIN).unwrap()
}

fn texts(files: &[(&str, &str)]) -> BTreeMap<String, String> {
    files
        .iter()
        .map(|(n, t)| (n.to_string(), t.to_string()))
        .collect()
}

fn job_issues(j: Job) -> Vec<Issue> {
    validate(&[j], &[])
}

fn monster_issues(m: Monster) -> Vec<Issue> {
    validate(&[], &[m])
}

fn has(issues: &[Issue], needle: &str) -> bool {
    issues.iter().any(|i| i.message.contains(needle))
}

fn load_err(jobs: &[(&str, &str)], monsters: &[(&str, &str)]) -> Vec<Issue> {
    load(&texts(jobs), &texts(monsters)).unwrap_err().0
}

fn read_dir(dir: &Path) -> BTreeMap<String, String> {
    let files: BTreeMap<_, _> = std::fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|x| x == "json"))
        .map(|p| {
            let name = p.file_name().unwrap().to_string_lossy().into_owned();
            (name, std::fs::read_to_string(&p).unwrap())
        })
        .collect();
    assert!(!files.is_empty(), "no *.json in {}", dir.display());
    files
}

pub(crate) fn shipped() -> Catalog {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../content");
    load(
        &read_dir(&dir.join("jobs")),
        &read_dir(&dir.join("monsters")),
    )
    .unwrap_or_else(|e| panic!("{e}"))
}

// --- ShippedContentTest ---

#[test]
fn shipped_content_loads() {
    let c = shipped();
    let mut jobs: Vec<_> = c.jobs.iter().map(|j| j.id.as_str()).collect();
    let mut monsters: Vec<_> = c.monsters.iter().map(|m| m.id.as_str()).collect();
    jobs.sort();
    monsters.sort();
    assert_eq!(jobs, ["apprentice", "knight", "squire"]);
    assert_eq!(monsters, ["goblin", "slime", "wolf"]);
}

#[test]
fn squire_and_knight_form_a_real_unlock_chain() {
    let c = shipped();
    let get = |id: &str| c.jobs.iter().find(|j| j.id == id).unwrap();
    assert!(get("squire").unlocks.contains(&"knight".to_string()));
    assert!(get("knight").prerequisites.contains(&"squire".to_string()));
    assert_eq!(find_job_graph_cycle(&c.jobs), None);
}

// --- ContentValidatorTest ---

#[test]
fn a_valid_job_and_monster_produce_zero_issues() {
    assert_eq!(validate(&[job()], &[monster()]), vec![]);
}

#[test]
fn wrong_schema_version_is_rejected() {
    assert!(has(
        &job_issues(Job {
            schema_version: 2,
            ..job()
        }),
        "schemaVersion"
    ));
    assert!(has(
        &monster_issues(Monster {
            schema_version: 0,
            ..monster()
        }),
        "schemaVersion"
    ));
}

#[test]
fn blank_or_uppercase_id_is_rejected() {
    assert!(has(
        &job_issues(Job {
            id: "".into(),
            ..job()
        }),
        "id must not be blank"
    ));
    assert!(has(
        &job_issues(Job {
            id: "  ".into(),
            ..job()
        }),
        "id must not be blank"
    ));
    assert!(has(
        &job_issues(Job {
            id: "Squire".into(),
            ..job()
        }),
        "id 'Squire'"
    ));
    assert!(has(
        &monster_issues(Monster {
            id: "gob-lin".into(),
            ..monster()
        }),
        "id 'gob-lin'"
    ));
}

#[test]
fn blank_display_name_is_rejected() {
    assert!(has(
        &job_issues(Job {
            display_name: " ".into(),
            ..job()
        }),
        "displayName"
    ));
    assert!(has(
        &monster_issues(Monster {
            display_name: "".into(),
            ..monster()
        }),
        "displayName"
    ));
}

#[test]
fn bad_dice_notation_is_rejected() {
    assert!(has(
        &job_issues(Job {
            hit_die: "d10".into(),
            ..job()
        }),
        "hitDie"
    ));
    assert!(has(
        &job_issues(Job {
            damage: "2x6".into(),
            ..job()
        }),
        "damage"
    ));
    assert!(has(
        &monster_issues(Monster {
            damage: "".into(),
            ..monster()
        }),
        "damage"
    ));
    for bad in [
        "1 d6", "1d", "1d6+", "1d6d", "-1d6", "1d6 2", "+", "abc", "1d0", "2d0+1",
    ] {
        assert_eq!(dice_count(bad), None, "{bad}");
    }
    for (ok, n) in [
        ("1d6", 1),
        (" 3d6 + 1 ", 3),
        ("2d8-1", 2),
        ("5", 0),
        ("-2", 0),
    ] {
        assert_eq!(dice_count(ok), Some(n), "{ok}");
    }
}

#[test]
fn hit_die_needs_exactly_one_die_but_damage_may_have_more() {
    assert!(has(
        &job_issues(Job {
            hit_die: "2d10".into(),
            ..job()
        }),
        "count == 1"
    ));
    assert!(has(
        &job_issues(Job {
            hit_die: "4".into(),
            ..job()
        }),
        "count == 1"
    ));
    assert_eq!(
        job_issues(Job {
            damage: "3d6+1".into(),
            ..job()
        }),
        vec![]
    );
}

#[test]
fn dice_values_that_overflow_are_issues() {
    let issues = validate(
        &[Job {
            hit_die: "1d99999999999".into(),
            ..job()
        }],
        &[Monster {
            damage: "99999999999d6".into(),
            ..monster()
        }],
    );
    for src in ["job:squire", "monster:goblin"] {
        assert!(
            issues
                .iter()
                .any(|i| i.source == src && i.message.contains("is not valid dice notation")),
            "{issues:?}"
        );
    }
}

#[test]
fn duplicate_ids_are_rejected() {
    assert!(has(&validate(&[job(), job()], &[]), "duplicate job id"));
    assert!(has(
        &validate(&[], &[monster(), monster()]),
        "duplicate monster id"
    ));
}

#[test]
fn dangling_prerequisite_and_unlock_are_rejected() {
    let issues = job_issues(Job {
        prerequisites: vec!["nonexistent".into()],
        unlocks: vec!["also_missing".into()],
        ..job()
    });
    assert!(has(&issues, "prerequisite 'nonexistent'"));
    assert!(has(&issues, "unlock 'also_missing'"));
}

#[test]
fn numeric_sanity_checks_reject_out_of_range_values() {
    let j = job;
    let m = monster;
    let cases = [
        (
            job_issues(Job {
                base_armor_class: 0,
                ..j()
            }),
            "baseArmorClass",
        ),
        (
            job_issues(Job {
                base_armor_class: 31,
                ..j()
            }),
            "baseArmorClass",
        ),
        (job_issues(Job { speed: 0, ..j() }), "speed"),
        (job_issues(Job { speed: 51, ..j() }), "speed"),
        (
            job_issues(Job {
                move_range_cells: -1,
                ..j()
            }),
            "moveRangeCells",
        ),
        (
            job_issues(Job {
                move_range_cells: 25,
                ..j()
            }),
            "moveRangeCells",
        ),
        (
            job_issues(Job {
                attack_range_cells: 0,
                ..j()
            }),
            "attackRangeCells",
        ),
        (
            job_issues(Job {
                attack_range_cells: 25,
                ..j()
            }),
            "attackRangeCells",
        ),
        (
            job_issues(Job {
                job_points_to_master: 0,
                ..j()
            }),
            "jobPointsToMaster",
        ),
        (
            monster_issues(Monster {
                armor_class: 31,
                ..m()
            }),
            "armorClass",
        ),
        (
            monster_issues(Monster {
                armor_class: 0,
                ..m()
            }),
            "armorClass",
        ),
        (monster_issues(Monster { speed: 0, ..m() }), "speed"),
        (
            monster_issues(Monster {
                move_range_cells: -1,
                ..m()
            }),
            "moveRangeCells",
        ),
        (
            monster_issues(Monster {
                attack_range_cells: 0,
                ..m()
            }),
            "attackRangeCells",
        ),
        (monster_issues(Monster { max_hp: 0, ..m() }), "maxHp"),
        (
            monster_issues(Monster {
                xp_reward: -1,
                ..m()
            }),
            "xpReward",
        ),
        (
            monster_issues(Monster {
                job_points_reward: -1,
                ..m()
            }),
            "jobPointsReward",
        ),
    ];
    for (issues, field) in cases {
        assert!(has(&issues, field), "{field}: {issues:?}");
    }
}

#[test]
fn unknown_attack_ability_and_stat_growth_keys_are_rejected() {
    assert!(has(
        &job_issues(Job {
            attack_ability: "luck".into(),
            ..job()
        }),
        "attackAbility"
    ));
    let growth = BTreeMap::from([("luck".to_string(), 1)]);
    assert!(has(
        &job_issues(Job {
            stat_growth: growth,
            ..job()
        }),
        "statGrowth"
    ));
}

#[test]
fn a_three_node_cycle_is_named_in_full() {
    let mk = |id: &str, prereq: &[&str], unlocks: &[&str]| Job {
        id: id.into(),
        prerequisites: prereq.iter().map(|s| s.to_string()).collect(),
        unlocks: unlocks.iter().map(|s| s.to_string()).collect(),
        ..job()
    };
    let jobs = [
        mk("a", &[], &["b"]),
        mk("b", &["a"], &["c"]),
        mk("c", &["b"], &["a"]),
    ];
    let cycle = find_job_graph_cycle(&jobs).unwrap();
    assert_eq!(cycle.len(), 4);
    assert_eq!(cycle.first(), cycle.last());
    let issues = validate(&jobs, &[]);
    let cycles: Vec<_> = issues
        .iter()
        .filter(|i| i.message.contains("cycle"))
        .collect();
    assert_eq!(cycles.len(), 1);
    assert!(cycles[0].message.contains(&cycle.join(" -> ")));

    let acyclic = [
        mk("squire", &[], &["knight"]),
        mk("knight", &["squire"], &[]),
    ];
    assert_eq!(find_job_graph_cycle(&acyclic), None);
}

#[test]
fn multiple_problems_produce_multiple_issues() {
    let bad = Job {
        id: "".into(),
        schema_version: 2,
        hit_die: "not-dice".into(),
        base_armor_class: -5,
        ..job()
    };
    assert!(job_issues(bad).len() >= 4);
}

// --- ContentLoaderTest ---

#[test]
fn load_returns_a_catalog_for_valid_content() {
    let c = load(
        &texts(&[("squire.json", SQUIRE)]),
        &texts(&[("goblin.json", GOBLIN)]),
    )
    .unwrap();
    assert_eq!(c.jobs, [job()]);
    assert_eq!(c.monsters, [monster()]);
}

#[test]
fn optional_fields_take_their_schema_defaults() {
    let minimal = r#"{"schemaVersion":1,"id":"x","displayName":"X","hitDie":"1d6",
        "baseArmorClass":10,"attackAbility":"dex","damage":"1d4","speed":5,"moveRangeCells":3}"#;
    let j: Job = serde_json::from_str(minimal).unwrap();
    assert_eq!((j.attack_range_cells, j.job_points_to_master), (1, 100));
    assert!(j.description.is_empty() && j.stat_growth.is_empty() && j.abilities_granted.is_empty());
}

#[test]
fn unknown_fields_are_rejected() {
    let job = SQUIRE.replace(
        "\"schemaVersion\": 1,",
        "\"schemaVersion\": 1, \"someFutureField\": 1,",
    );
    let mon = GOBLIN.replace("\"maxHp\"", "\"hp\": 7, \"maxHp\"");
    let issues = load_err(&[("squire.json", &job)], &[("goblin.json", &mon)]);
    assert!(
        issues
            .iter()
            .any(|i| i.source == "squire.json" && i.message.contains("someFutureField")),
        "{issues:?}"
    );
    assert!(
        issues
            .iter()
            .any(|i| i.source == "goblin.json" && i.message.contains("`hp`")),
        "{issues:?}"
    );
}

#[test]
fn a_missing_required_field_or_bad_json_is_rejected() {
    let missing = SQUIRE.replace("\"hitDie\": \"1d10\",", "");
    let issues = load_err(&[("squire.json", &missing), ("broken.json", "{")], &[]);
    assert!(
        issues
            .iter()
            .any(|i| i.source == "squire.json" && i.message.contains("hitDie")),
        "{issues:?}"
    );
    assert!(
        issues.iter().any(|i| i.source == "broken.json"),
        "{issues:?}"
    );
}

#[test]
fn a_wrongly_typed_or_overflowing_number_is_rejected() {
    let typed = GOBLIN.replace("\"maxHp\": 7", "\"maxHp\": \"7\"");
    let huge = GOBLIN.replace("\"maxHp\": 7", "\"maxHp\": 99999999999");
    let issues = load_err(&[], &[("a.json", &typed), ("b.json", &huge)]);
    assert_eq!(issues.len(), 2, "{issues:?}");
}

#[test]
fn validation_failures_are_rejected_through_load() {
    let bad_damage = GOBLIN.replace("\"1d6+2\"", "\"not-dice\"");
    assert!(has(
        &load_err(&[], &[("goblin.json", &bad_damage)]),
        "damage"
    ));
    assert!(has(
        &load_err(&[("a.json", SQUIRE), ("b.json", SQUIRE)], &[]),
        "duplicate"
    ));
    let dangling = SQUIRE.replace(
        "\"prerequisites\": []",
        "\"prerequisites\": [\"nonexistent\"]",
    );
    assert!(has(
        &load_err(&[("squire.json", &dangling)], &[]),
        "nonexistent"
    ));
    let bad_ac = GOBLIN.replace("\"armorClass\": 13", "\"armorClass\": 99");
    assert!(has(
        &load_err(&[], &[("goblin.json", &bad_ac)]),
        "armorClass"
    ));
}

#[test]
fn every_issue_is_listed_not_just_the_first() {
    let broken = r#"{"schemaVersion":2,"id":"","displayName":"","hitDie":"not-dice",
        "baseArmorClass":-5,"attackAbility":"luck","damage":"1d8","speed":8,"moveRangeCells":4}"#;
    let err = load(&texts(&[("broken.json", broken)]), &BTreeMap::new()).unwrap_err();
    assert!(err.0.len() >= 5, "{:?}", err.0);
    assert!(err.to_string().lines().count() > 2);
}
