//! The character: its save shape (port of the Kotlin `CharacterState`), the
//! new-character factory and the rules `POST`/`PUT /v1/character` enforce
//! (docs/SERVER.md §5).

pub mod save_codec;

use indexmap::{IndexMap, IndexSet};
use serde::{Deserialize, Serialize};

use crate::content::{Catalog, Job};

pub const CURRENT_SCHEMA_VERSION: i32 = 1;
pub const MAX_NAME: usize = 24;

/// Fixed, not rolled: a new character must be deterministic and testable.
pub const STARTING_SCORES: AbilityScores = AbilityScores {
    str: 14,
    dex: 12,
    con: 13,
    int: 10,
    wis: 10,
    cha: 10,
};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AbilityScores {
    pub str: i32,
    pub dex: i32,
    pub con: i32,
    pub int: i32,
    pub wis: i32,
    pub cha: i32,
}

/// One bestiary line: what it is, and how many of them you have put down.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BestiaryEntry {
    pub monster_id: String,
    #[serde(default)]
    pub times_defeated: i32,
}

/// The whole save. Field order, defaults and the insertion-ordered maps and
/// sets match kotlinx, so `save_codec::encode` writes what the app writes.
/// Unknown keys are ignored, as the app ignores them.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CharacterState {
    #[serde(default = "current_schema_version")]
    pub schema_version: i32,
    pub name: String,
    pub base_scores: AbilityScores,
    pub current_job_id: String,
    #[serde(default = "one")]
    pub level: i32,
    #[serde(default)]
    pub xp: i32,
    /// Per job id.
    #[serde(default)]
    pub job_points: IndexMap<String, i32>,
    #[serde(default)]
    pub unlocked_job_ids: IndexSet<String>,
    /// Abilities live on the character, FFT-style, so they carry between jobs.
    #[serde(default)]
    pub learned_ability_ids: IndexSet<String>,
    #[serde(default)]
    pub bestiary: IndexMap<String, BestiaryEntry>,
}

fn current_schema_version() -> i32 {
    CURRENT_SCHEMA_VERSION
}

fn one() -> i32 {
    1
}

/// Jobs a brand-new character may start as: those with no prerequisites.
pub fn starting_jobs(jobs: &[Job]) -> impl Iterator<Item = &Job> {
    jobs.iter().filter(|j| j.prerequisites.is_empty())
}

pub fn new_character(name: String, starting_job: &Job) -> CharacterState {
    CharacterState {
        schema_version: CURRENT_SCHEMA_VERSION,
        name,
        base_scores: STARTING_SCORES,
        current_job_id: starting_job.id.clone(),
        level: 1,
        xp: 0,
        job_points: IndexMap::new(),
        unlocked_job_ids: IndexSet::new(),
        learned_ability_ids: IndexSet::new(),
        bestiary: IndexMap::new(),
    }
}

/// Why a request broke the character rules: the §5 error code (always HTTP
/// 400) and a message safe to show.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rejection {
    pub code: &'static str,
    pub message: &'static str,
}

const fn rejection(code: &'static str, message: &'static str) -> Rejection {
    Rejection { code, message }
}

const NOT_JSON: Rejection = rejection("bad_request", "The request was malformed.");
const BAD_NAME: Rejection = rejection("invalid_name", "Name must be 1 to 24 characters.");
const NOT_STARTING: Rejection = rejection("invalid_job", "That is not a starting job.");
const UNKNOWN_JOB: Rejection =
    rejection("invalid_job", "Character names a job that does not exist.");
const UNKNOWN_ABILITY: Rejection = rejection(
    "invalid_job",
    "Character names an ability that does not exist.",
);
const NOT_A_SAVE: Rejection = rejection("invalid_state", "Character data is not a valid save.");
const WRONG_VERSION: Rejection = rejection("invalid_state", "That save version is not supported.");
const BAD_SAVED_NAME: Rejection = rejection("invalid_state", "Name must be 1 to 24 characters.");
const RENAMED: Rejection = rejection("invalid_state", "A character cannot be renamed.");
const LOST_LEVELS: Rejection = rejection("invalid_state", "A character cannot lose levels.");

/// Counted in UTF-16 units, as the app's Kotlin `String.length` counts it.
fn name_ok(name: &str) -> bool {
    !name.is_empty() && name.encode_utf16().count() <= MAX_NAME
}

/// `POST /v1/character`: the trimmed name and a starting job make a new character.
pub fn create(
    catalog: &Catalog,
    name: &str,
    starting_job_id: &str,
) -> Result<CharacterState, Rejection> {
    let name = name.trim();
    if !name_ok(name) {
        return Err(BAD_NAME);
    }
    let job = starting_jobs(&catalog.jobs)
        .find(|j| j.id == starting_job_id)
        .ok_or(NOT_STARTING)?;
    Ok(new_character(name.to_owned(), job))
}

/// `PUT /v1/character`: decodes (and migrates) the client's save and checks it
/// against the stored one; `None` is the one-time import of a pre-server save.
///
/// shortcut: client-authoritative progress, replaced when server combat ships and PUT goes away
pub fn update(
    catalog: &Catalog,
    text: &str,
    stored: Option<&CharacterState>,
) -> Result<CharacterState, Rejection> {
    let state = save_codec::decode(text).map_err(|e| match e.classify() {
        serde_json::error::Category::Syntax | serde_json::error::Category::Eof => NOT_JSON,
        _ => NOT_A_SAVE,
    })?;
    if state.schema_version != CURRENT_SCHEMA_VERSION {
        return Err(WRONG_VERSION);
    }
    let known_job = |id: &String| catalog.jobs.iter().any(|j| &j.id == id);
    let mut job_ids = std::iter::once(&state.current_job_id)
        .chain(&state.unlocked_job_ids)
        .chain(state.job_points.keys());
    if !job_ids.all(known_job) {
        return Err(UNKNOWN_JOB);
    }
    let known_ability = |id: &String| {
        catalog
            .jobs
            .iter()
            .any(|j| j.abilities_granted.contains(id))
    };
    if !state.learned_ability_ids.iter().all(known_ability) {
        return Err(UNKNOWN_ABILITY);
    }
    match stored {
        // The import skips create(), so the name is checked here; the job may be past the starting ones.
        None if state.name.trim().is_empty() || !name_ok(&state.name) => Err(BAD_SAVED_NAME),
        Some(s) if state.name != s.name => Err(RENAMED),
        Some(s) if state.level < s.level => Err(LOST_LEVELS),
        _ => Ok(state),
    }
}

#[cfg(test)]
mod tests;
