//! Encounter + character + catalog -> a ready battle (port of the Kotlin
//! `BattleBuilder` and `StatCalculator.derive`).

use serde::Deserialize;

use super::dice::DiceExpr;
use super::hex::{self, HexCoord};
use super::{CombatUnit, DerivedStats};
use crate::character::{AbilityScores, CharacterState};
use crate::content::{Catalog, Job, Monster};

pub const PLAYER_UNIT_ID: &str = "player";
pub const MONSTER_START_RING_CELLS: i32 = 4;
/// 12 m radius, a 469-cell board.
pub const DEFAULT_BOARD_RADIUS_CELLS: i32 = 12;

/// What a battle is built from. The geographic anchor stays with the caller:
/// combat only needs the board's size.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Encounter {
    pub monster_ids: Vec<String>,
    #[serde(default = "default_radius")]
    pub board_radius_cells: i32,
}

fn default_radius() -> i32 {
    DEFAULT_BOARD_RADIUS_CELLS
}

/// A board plus the units standing on it, ready for `CombatEngine::new`.
#[derive(Debug, Clone, PartialEq)]
pub struct Battle {
    pub board_radius_cells: i32,
    pub units: Vec<CombatUnit>,
}

/// Player at the origin; monsters spread evenly round ring 4. Errs on content
/// the battle cannot be built from, as the Kotlin builder throws.
pub fn build(
    encounter: &Encounter,
    character: &CharacterState,
    catalog: &Catalog,
) -> Result<Battle, String> {
    // A save naming a job content no longer ships still gets a battle.
    let job = catalog
        .jobs
        .iter()
        .find(|j| j.id == character.current_job_id)
        .or(catalog.jobs.first())
        .ok_or("BattleBuilder: the catalog has no jobs")?;
    let stats = derive_stats(&character.base_scores, job, character.level)?;
    let mut units = vec![CombatUnit {
        id: PLAYER_UNIT_ID.to_owned(),
        display_name: character.name.clone(),
        player_controlled: true,
        source_id: job.id.clone(),
        current_hp: stats.max_hp,
        stats,
        position: hex::ORIGIN,
        atb_gauge: 0.0,
    }];

    let ring = hex::ring(hex::ORIGIN, MONSTER_START_RING_CELLS);
    let count = encounter.monster_ids.len();
    if count > ring.len() {
        return Err(format!(
            "BattleBuilder: {count} monsters exceed the {} cells on ring {MONSTER_START_RING_CELLS}",
            ring.len()
        ));
    }
    let step = ring.len().checked_div(count).unwrap_or(0);
    for (index, monster_id) in encounter.monster_ids.iter().enumerate() {
        let monster = catalog
            .monsters
            .iter()
            .find(|m| &m.id == monster_id)
            .ok_or_else(|| {
                format!("BattleBuilder: unknown monster id \"{monster_id}\" not in catalog")
            })?;
        units.push(monster_unit(
            monster,
            index,
            ring[(index * step) % ring.len()],
        )?);
    }
    Ok(Battle {
        board_radius_cells: encounter.board_radius_cells,
        units,
    })
}

fn monster_unit(m: &Monster, index: usize, position: HexCoord) -> Result<CombatUnit, String> {
    Ok(CombatUnit {
        id: format!("{}#{index}", m.id),
        display_name: m.display_name.clone(),
        player_controlled: false,
        source_id: m.id.clone(),
        stats: DerivedStats {
            max_hp: m.max_hp,
            armor_class: m.armor_class,
            attack_bonus: m.attack_bonus,
            damage: dice(&m.damage)?,
            speed: m.speed,
            move_range_cells: m.move_range_cells,
            attack_range_cells: m.attack_range_cells,
        },
        current_hp: m.max_hp,
        position,
        atb_gauge: 0.0,
    })
}

fn dice(text: &str) -> Result<DiceExpr, String> {
    DiceExpr::parse(text).ok_or_else(|| format!("not a valid dice expression: \"{text}\""))
}

/// Job + level -> stats, exactly the Kotlin `StatCalculator.derive`:
/// scores grow by `statGrowth * (level - 1)`; max HP is the full hit die plus
/// `(sides / 2 + 1)` per later level, each plus the CON modifier, at least 1.
pub fn derive_stats(base: &AbilityScores, job: &Job, level: i32) -> Result<DerivedStats, String> {
    let score = |key: &str| {
        let base = match key {
            "str" => base.str,
            "dex" => base.dex,
            "con" => base.con,
            "int" => base.int,
            "wis" => base.wis,
            "cha" => base.cha,
            _ => return Err(format!("unknown ability score key: \"{key}\"")),
        };
        Ok(base + job.stat_growth.get(key).copied().unwrap_or(0) * (level - 1))
    };
    let modifier = |key: &str| score(key).map(|s| (s - 10).div_euclid(2));
    let con = modifier("con")?;
    let attack = modifier(&job.attack_ability)?;
    let sides = dice(&job.hit_die)?.sides;
    let mut damage = dice(&job.damage)?;
    damage.modifier += attack;
    Ok(DerivedStats {
        max_hp: ((sides + con) + (level - 1) * ((sides / 2 + 1) + con)).max(1),
        armor_class: job.base_armor_class + modifier("dex")?,
        attack_bonus: attack + 2 + (level - 1) / 4,
        damage,
        speed: job.speed,
        move_range_cells: job.move_range_cells,
        attack_range_cells: job.attack_range_cells,
    })
}
