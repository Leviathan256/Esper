//! Replays the Kotlin engine's recordings in `content/fixtures/combat/` (written
//! by `CombatRecordingsTest`) and mirrors the Kotlin RNG and ATB tests.

use std::path::Path;

use indexmap::IndexMap;
use serde::Deserialize;

use super::battle::{self, Encounter};
use super::dice::{DiceExpr, JavaRandom};
use super::hex::HexCoord;
use super::*;
use crate::character::CharacterState;
use crate::content::tests::shipped;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Recording {
    seed: i64,
    encounter: Encounter,
    character: CharacterState,
    /// Pets and companions: player-controlled units added after the builder.
    allies: Vec<CombatUnit>,
    units: Vec<CombatUnit>,
    turns: Vec<Turn>,
    result: BattleResult,
    final_units: Vec<CombatUnit>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Turn {
    actor: String,
    /// Every unit's gauge once the actor is ready.
    gauges: IndexMap<String, f64>,
    /// Submitted in order; the last is the one `SimpleAi` chose.
    actions: Vec<Step>,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Step {
    action: CombatAction,
    events: Vec<CombatEvent>,
}

fn replay(name: &str, rec: Recording) {
    let mut battle = battle::build(&rec.encounter, &rec.character, &shipped()).unwrap();
    battle.units.extend(rec.allies);
    assert_eq!(battle.units, rec.units, "{name}: built units");
    let mut engine = CombatEngine::new(
        battle.board_radius_cells,
        battle.units,
        JavaRandom::new(rec.seed),
    );
    for (i, turn) in rec.turns.iter().enumerate() {
        let actor = engine.advance_to_next_actor().expect("an actor").clone();
        assert_eq!(actor.id, turn.actor, "{name} turn {i}: actor");
        let gauges: IndexMap<_, _> = engine
            .units()
            .iter()
            .map(|u| (u.id.clone(), u.atb_gauge))
            .collect();
        assert_eq!(gauges, turn.gauges, "{name} turn {i}: gauges");
        let chosen = &turn.actions.last().unwrap().action;
        assert_eq!(
            &ai::choose_action(&engine, &actor),
            chosen,
            "{name} turn {i}: AI"
        );
        for step in &turn.actions {
            assert_eq!(
                engine.submit_action(&step.action),
                step.events,
                "{name} turn {i}: {:?}",
                step.action
            );
        }
    }
    assert_eq!(engine.result(), Some(rec.result), "{name}: result");
    assert_eq!(engine.units(), rec.final_units, "{name}: final units");
    assert!(engine.advance_to_next_actor().is_none());
    assert_eq!(
        engine.submit_action(&CombatAction::Wait),
        rejected("none", "battle already over".into())
    );
}

#[test]
fn every_kotlin_recording_replays_identically() {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../content/fixtures/combat");
    let mut names = Vec::new();
    for entry in std::fs::read_dir(dir).unwrap() {
        let path = entry.unwrap().path();
        let name = path.file_stem().unwrap().to_str().unwrap().to_owned();
        let rec: Recording = serde_json::from_str(&std::fs::read_to_string(&path).unwrap())
            .unwrap_or_else(|e| panic!("{name}: {e}"));
        replay(&name, rec);
        names.push(name);
    }
    names.sort();
    for wanted in ["solo", "with_pet", "with_companion"] {
        assert!(
            names.iter().any(|n| n == wanted),
            "missing recording {wanted}"
        );
    }
}

// --- DiceExprTest: java.util.Random is a specified 48-bit LCG ---

#[test]
fn seeded_random_matches_java_util_random() {
    let mut rng = JavaRandom::new(42);
    let seq: Vec<_> = (0..10).map(|_| rng.next_int(20)).collect();
    assert_eq!(seq, [10, 3, 8, 4, 10, 5, 5, 18, 19, 13]);
}

#[test]
fn dice_notation_round_trips() {
    for text in ["1d8+2", "2d6-1", "1d4", "0", "-3"] {
        assert_eq!(DiceExpr::parse(text).unwrap().to_string(), text);
    }
    assert_eq!(
        DiceExpr::parse(" 1d6 + 2 "),
        Some(DiceExpr {
            count: 1,
            sides: 6,
            modifier: 2
        })
    );
    for bad in ["", "d6", "1d", "1d6+", "1d6+\t2", "1d6+99999999999", "x"] {
        assert_eq!(DiceExpr::parse(bad), None, "{bad:?}");
    }
}

// --- AtbOrderTest ---

fn unit(id: &str, player: bool, q: i32, speed: i32) -> CombatUnit {
    CombatUnit {
        id: id.into(),
        display_name: id.into(),
        player_controlled: player,
        source_id: id.into(),
        stats: DerivedStats {
            max_hp: 20,
            armor_class: 1,
            attack_bonus: 5,
            damage: DiceExpr::parse("1d6").unwrap(),
            speed,
            move_range_cells: 4,
            attack_range_cells: 1,
        },
        current_hp: 20,
        position: HexCoord::new(q, 0),
        atb_gauge: 0.0,
    }
}

#[test]
fn gauges_pause_while_an_action_resolves() {
    let units = vec![
        unit("A", true, 0, 37),
        unit("B", false, 1, 30),
        unit("C", false, 3, 11),
    ];
    let mut engine = CombatEngine::new(6, units, JavaRandom::new(1));
    let actor = engine.advance_to_next_actor().unwrap().id.clone();
    let before: Vec<f64> = engine.units().iter().map(|u| u.atb_gauge).collect();
    assert_eq!(
        (actor.as_str(), before.as_slice()),
        ("A", &[111.0, 90.0, 33.0][..])
    );

    let events = engine.submit_action(&CombatAction::Attack {
        target_id: "B".into(),
    });
    assert!(matches!(events[0], CombatEvent::Attacked { .. }));
    let after: Vec<f64> = engine.units().iter().map(|u| u.atb_gauge).collect();
    // Only the actor's gauge moved, and by exactly GAUGE_MAX: overflow carries.
    assert_eq!(after, [11.0, 90.0, 33.0]);
    // A rejected action spends nothing either.
    engine.advance_to_next_actor();
    let ready: Vec<f64> = engine.units().iter().map(|u| u.atb_gauge).collect();
    engine.submit_action(&CombatAction::Attack {
        target_id: "C".into(),
    });
    assert_eq!(
        engine
            .units()
            .iter()
            .map(|u| u.atb_gauge)
            .collect::<Vec<_>>(),
        ready
    );
}

#[test]
fn ties_break_on_the_lowest_id_and_leftover_readiness_is_not_skipped() {
    let units = vec![unit("B", true, 0, 100), unit("A", true, 1, 100)];
    let mut engine = CombatEngine::new(6, units, JavaRandom::new(1));
    let mut order = Vec::new();
    for _ in 0..4 {
        order.push(engine.advance_to_next_actor().unwrap().id.clone());
        engine.submit_action(&CombatAction::Wait);
    }
    assert_eq!(order, ["A", "B", "A", "B"]);
}

#[test]
fn no_current_actor_is_rejected_and_zero_speed_gives_up() {
    let mut engine = CombatEngine::new(6, vec![unit("A", true, 0, 10)], JavaRandom::new(1));
    assert_eq!(
        engine.submit_action(&CombatAction::Wait),
        rejected("none", "no current actor".into())
    );
    let mut engine = CombatEngine::new(
        6,
        vec![unit("A", true, 0, 0), unit("B", false, 1, 0)],
        JavaRandom::new(1),
    );
    assert!(
        engine.advance_to_next_actor().is_none(),
        "all-zero speed gives up"
    );
}
