//! Combat: ATB turn order and action resolution (port of the Kotlin
//! `CombatEngine`, `BattleBuilder` and `SimpleAi`). With the same seed it plays
//! exactly what the Kotlin engine plays; the recordings in
//! `content/fixtures/combat/` guard that until `engine/` is deleted (ADR-0002).

pub mod ai;
pub mod battle;
pub mod dice;
pub mod hex;

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use dice::{AttackRoll, DiceExpr, JavaRandom};
use hex::HexCoord;

pub const GAUGE_MAX: f64 = 100.0;

/// Guards against an all-zero-speed deadlock.
pub const MAX_TICKS_PER_TURN: u32 = 10_000;

/// Everything combat needs about a unit, already computed.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DerivedStats {
    pub max_hp: i32,
    pub armor_class: i32,
    pub attack_bonus: i32,
    pub damage: DiceExpr,
    pub speed: i32,
    pub move_range_cells: i32,
    pub attack_range_cells: i32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CombatUnit {
    /// e.g. "player", "goblin#0".
    pub id: String,
    pub display_name: String,
    pub player_controlled: bool,
    /// Job id or monster id.
    pub source_id: String,
    pub stats: DerivedStats,
    pub current_hp: i32,
    pub position: HexCoord,
    #[serde(default)]
    pub atb_gauge: f64,
}

impl CombatUnit {
    pub fn alive(&self) -> bool {
        self.current_hp > 0
    }
}

/// What a unit can do on its turn; every action costs the same flat gauge.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
pub enum CombatAction {
    Move { to: HexCoord },
    Attack { target_id: String },
    Wait,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum BattleResult {
    Victory,
    Defeat,
}

/// What happened, in order: the client draws these and recomputes nothing.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
pub enum CombatEvent {
    Moved {
        unit_id: String,
        from: HexCoord,
        to: HexCoord,
    },
    Attacked {
        attacker_id: String,
        target_id: String,
        roll: AttackRoll,
        damage: i32,
        target_hp_after: i32,
    },
    Defeated {
        unit_id: String,
    },
    /// Illegal: nothing changed and no gauge was spent.
    ActionRejected {
        unit_id: String,
        reason: String,
    },
    /// Emitted exactly once per battle.
    BattleEnded {
        result: BattleResult,
    },
}

fn rejected(unit_id: &str, reason: String) -> Vec<CombatEvent> {
    vec![CombatEvent::ActionRejected {
        unit_id: unit_id.to_owned(),
        reason,
    }]
}

/// Discrete ticks, not a timer: `advance_to_next_actor` adds each living unit's
/// speed to its gauge until one reaches [`GAUGE_MAX`]; acting subtracts it, so
/// overflow carries. Gauges only move in `advance_to_next_actor`, never while an
/// action resolves.
pub struct CombatEngine {
    board: HashSet<HexCoord>,
    /// Insertion order, as the Kotlin `LinkedHashMap`.
    units: Vec<CombatUnit>,
    rng: JavaRandom,
    current_actor: Option<usize>,
    result: Option<BattleResult>,
}

impl CombatEngine {
    pub fn new(board_radius_cells: i32, units: Vec<CombatUnit>, rng: JavaRandom) -> Self {
        Self {
            board: hex::cells_within_radius(hex::ORIGIN, board_radius_cells)
                .into_iter()
                .collect(),
            units,
            rng,
            current_actor: None,
            result: None,
        }
    }

    pub fn units(&self) -> &[CombatUnit] {
        &self.units
    }

    pub fn unit(&self, id: &str) -> Option<&CombatUnit> {
        self.units.iter().find(|u| u.id == id)
    }

    /// None while the battle is in progress.
    pub fn result(&self) -> Option<BattleResult> {
        self.result
    }

    pub fn is_over(&self) -> bool {
        self.result.is_some()
    }

    pub fn current_actor(&self) -> Option<&CombatUnit> {
        self.current_actor.map(|i| &self.units[i])
    }

    /// Ticks every living gauge until one reaches [`GAUGE_MAX`] and returns that
    /// unit; ties go to the lowest id. None once the battle is over.
    pub fn advance_to_next_actor(&mut self) -> Option<&CombatUnit> {
        if self.is_over() {
            return None;
        }
        // A tie last call can leave a unit already past GAUGE_MAX: check before ticking.
        self.current_actor = self.ready_actor();
        for _ in 0..MAX_TICKS_PER_TURN {
            if self.current_actor.is_some() {
                break;
            }
            for u in self.units.iter_mut().filter(|u| u.alive()) {
                u.atb_gauge += f64::from(u.stats.speed);
            }
            self.current_actor = self.ready_actor();
        }
        self.current_actor()
    }

    fn ready_actor(&self) -> Option<usize> {
        (0..self.units.len())
            .filter(|&i| self.units[i].alive() && self.units[i].atb_gauge >= GAUGE_MAX)
            .min_by(|&a, &b| self.units[a].id.cmp(&self.units[b].id))
    }

    /// Applies `action` for the current actor and spends [`GAUGE_MAX`] of its
    /// gauge. An illegal action yields `ActionRejected` and spends nothing.
    pub fn submit_action(&mut self, action: &CombatAction) -> Vec<CombatEvent> {
        if self.is_over() {
            return rejected("none", "battle already over".into());
        }
        let Some(actor) = self.current_actor else {
            return rejected("none", "no current actor".into());
        };
        match action {
            CombatAction::Wait => {
                self.commit(actor);
                Vec::new()
            }
            CombatAction::Move { to } => self.handle_move(actor, *to),
            CombatAction::Attack { target_id } => self.handle_attack(actor, target_id),
        }
    }

    /// Reachable cells in BFS order, the unit's own cell first.
    pub fn legal_moves(&self, unit_id: &str) -> Vec<HexCoord> {
        let Some(actor) = self.unit(unit_id) else {
            return Vec::new();
        };
        let blocked = self
            .units
            .iter()
            .filter(|u| u.alive() && u.id != unit_id)
            .map(|u| u.position)
            .collect();
        hex::reachable_cells(
            actor.position,
            actor.stats.move_range_cells,
            &self.board,
            &blocked,
        )
    }

    pub fn attackable_targets(&self, unit_id: &str) -> Vec<&CombatUnit> {
        let Some(actor) = self.unit(unit_id) else {
            return Vec::new();
        };
        self.units
            .iter()
            .filter(|c| {
                c.id != unit_id
                    && c.alive()
                    && c.player_controlled != actor.player_controlled
                    && actor.position.distance_to(c.position) <= actor.stats.attack_range_cells
            })
            .collect()
    }

    fn handle_move(&mut self, actor: usize, to: HexCoord) -> Vec<CombatEvent> {
        let id = self.units[actor].id.clone();
        if !self.legal_moves(&id).contains(&to) {
            return rejected(&id, format!("illegal move to {to}"));
        }
        let from = self.units[actor].position;
        self.units[actor].position = to;
        self.commit(actor);
        vec![CombatEvent::Moved {
            unit_id: id,
            from,
            to,
        }]
    }

    fn handle_attack(&mut self, actor: usize, target_id: &str) -> Vec<CombatEvent> {
        let a = &self.units[actor];
        let target = self.units.iter().position(|u| u.id == target_id);
        let Some(target) = target.filter(|&t| {
            self.units[t].alive() && self.units[t].player_controlled != a.player_controlled
        }) else {
            return rejected(&a.id, format!("no valid target {target_id}"));
        };
        let t = &self.units[target];
        if a.position.distance_to(t.position) > a.stats.attack_range_cells {
            return rejected(&a.id, format!("target {target_id} out of range"));
        }

        let roll = dice::roll_attack(a.stats.attack_bonus, t.stats.armor_class, &mut self.rng);
        let damage = if roll.hit {
            dice::roll_damage(&a.stats.damage, roll.critical, &mut self.rng)
        } else {
            0
        };
        let hp_after = (t.current_hp - damage).max(0);
        let attacker_id = a.id.clone();
        self.units[target].current_hp = hp_after;
        self.commit(actor);

        let mut events = vec![CombatEvent::Attacked {
            attacker_id,
            target_id: target_id.to_owned(),
            roll,
            damage,
            target_hp_after: hp_after,
        }];
        if hp_after <= 0 {
            events.push(CombatEvent::Defeated {
                unit_id: target_id.to_owned(),
            });
        }
        if let Some(result) = self.check_battle_end() {
            events.push(CombatEvent::BattleEnded { result });
        }
        events
    }

    fn commit(&mut self, actor: usize) {
        self.units[actor].atb_gauge -= GAUGE_MAX;
        self.current_actor = None;
    }

    /// The outcome the first time one side is wiped out; None every time after.
    fn check_battle_end(&mut self) -> Option<BattleResult> {
        if self.result.is_some() {
            return None;
        }
        let living = |player: bool| {
            self.units
                .iter()
                .any(|u| u.player_controlled == player && u.alive())
        };
        self.result = if !living(true) {
            Some(BattleResult::Defeat)
        } else if !living(false) {
            Some(BattleResult::Victory)
        } else {
            None
        };
        self.result
    }
}

#[cfg(test)]
mod tests;
