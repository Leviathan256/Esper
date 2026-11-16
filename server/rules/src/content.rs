//! Loads and validates `content/` (docs/SERVER.md §11). Mirrors the Kotlin
//! `ContentLoader` + `ContentValidator` rule for rule, except that unknown
//! fields are rejected here: the server is the authority, so a field it does
//! not know is a mistake, not a newer file. Takes text, never files.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;

use serde::Deserialize;

pub const ABILITY_KEYS: [&str; 6] = ["str", "dex", "con", "int", "wis", "cha"];

/// One job, authored as `content/jobs/<id>.json` (content/schema/job.schema.md).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Job {
    pub schema_version: i32,
    pub id: String,
    pub display_name: String,
    #[serde(default)]
    pub description: String,
    pub hit_die: String,
    pub base_armor_class: i32,
    pub attack_ability: String,
    pub damage: String,
    pub speed: i32,
    pub move_range_cells: i32,
    #[serde(default = "one")]
    pub attack_range_cells: i32,
    #[serde(default)]
    pub stat_growth: BTreeMap<String, i32>,
    #[serde(default)]
    pub abilities_granted: Vec<String>,
    #[serde(default)]
    pub prerequisites: Vec<String>,
    #[serde(default)]
    pub unlocks: Vec<String>,
    #[serde(default = "hundred")]
    pub job_points_to_master: i32,
}

/// One monster, authored as `content/monsters/<id>.json` (content/schema/monster.schema.md).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Monster {
    pub schema_version: i32,
    pub id: String,
    pub display_name: String,
    pub max_hp: i32,
    pub armor_class: i32,
    pub attack_bonus: i32,
    pub damage: String,
    pub speed: i32,
    pub move_range_cells: i32,
    #[serde(default = "one")]
    pub attack_range_cells: i32,
    pub xp_reward: i32,
    pub job_points_reward: i32,
    #[serde(default)]
    pub bestiary_text: String,
}

fn one() -> i32 {
    1
}

fn hundred() -> i32 {
    100
}

/// Everything the game knows, after parsing and validation.
#[derive(Debug, Clone)]
pub struct Catalog {
    pub jobs: Vec<Job>,
    pub monsters: Vec<Monster>,
}

/// One problem with one content file.
#[derive(Debug, Clone, PartialEq)]
pub struct Issue {
    pub source: String,
    pub message: String,
}

/// Every issue found, not just the first: one CI run should list them all.
pub struct ContentError(pub Vec<Issue>);

// Debug is what `main` prints when it returns this, so make it the readable list.
impl fmt::Debug for ContentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Display::fmt(self, f)
    }
}

impl fmt::Display for ContentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "content invalid:")?;
        for i in &self.0 {
            write!(f, "\n  - {}: {}", i.source, i.message)?;
        }
        Ok(())
    }
}

impl std::error::Error for ContentError {}

/// Maps are filename -> file text. Parses, then validates.
pub fn load(
    jobs: &BTreeMap<String, String>,
    monsters: &BTreeMap<String, String>,
) -> Result<Catalog, ContentError> {
    let mut issues = Vec::new();
    let jobs = parse_all::<Job>(jobs, &mut issues);
    let monsters = parse_all::<Monster>(monsters, &mut issues);
    issues.extend(validate(&jobs, &monsters));
    if issues.is_empty() {
        Ok(Catalog { jobs, monsters })
    } else {
        Err(ContentError(issues))
    }
}

fn parse_all<T: for<'de> Deserialize<'de>>(
    texts: &BTreeMap<String, String>,
    issues: &mut Vec<Issue>,
) -> Vec<T> {
    let mut out = Vec::new();
    for (source, text) in texts {
        match serde_json::from_str(text) {
            Ok(v) => out.push(v),
            Err(e) => issues.push(issue(source, format!("failed to parse: {e}"))),
        }
    }
    out
}

fn issue(source: &str, message: String) -> Issue {
    Issue {
        source: source.to_string(),
        message,
    }
}

/// Returns every issue found. Empty means valid.
pub fn validate(jobs: &[Job], monsters: &[Monster]) -> Vec<Issue> {
    let mut issues = Vec::new();
    let job_ids: HashSet<&str> = jobs.iter().map(|j| j.id.as_str()).collect();

    let mut seen = HashSet::new();
    for job in jobs {
        let src = &source("job", &job.id);
        let mut push = |m: String| issues.push(issue(src, m));
        common(job.schema_version, &job.id, &job.display_name, &mut push);
        if !job.id.trim().is_empty() && !seen.insert(job.id.as_str()) {
            push(format!("duplicate job id '{}'", job.id));
        }
        dice(&job.hit_die, "hitDie", true, &mut push);
        dice(&job.damage, "damage", false, &mut push);
        range(job.base_armor_class, 1, 30, "baseArmorClass", &mut push);
        range(job.speed, 1, 50, "speed", &mut push);
        range(job.move_range_cells, 0, 24, "moveRangeCells", &mut push);
        range(job.attack_range_cells, 1, 24, "attackRangeCells", &mut push);
        if job.job_points_to_master < 1 {
            push(format!(
                "jobPointsToMaster {} must be >= 1",
                job.job_points_to_master
            ));
        }
        ability_key(&job.attack_ability, "attackAbility", &mut push);
        for key in job.stat_growth.keys() {
            ability_key(key, "statGrowth key", &mut push);
        }
        for p in &job.prerequisites {
            if !job_ids.contains(p.as_str()) {
                push(format!(
                    "prerequisite '{p}' does not resolve to a known job"
                ));
            }
        }
        for u in &job.unlocks {
            if !job_ids.contains(u.as_str()) {
                push(format!("unlock '{u}' does not resolve to a known job"));
            }
        }
    }

    let mut seen = HashSet::new();
    for m in monsters {
        let src = &source("monster", &m.id);
        let mut push = |msg: String| issues.push(issue(src, msg));
        common(m.schema_version, &m.id, &m.display_name, &mut push);
        if !m.id.trim().is_empty() && !seen.insert(m.id.as_str()) {
            push(format!("duplicate monster id '{}'", m.id));
        }
        dice(&m.damage, "damage", false, &mut push);
        if m.max_hp < 1 {
            push(format!("maxHp {} must be >= 1", m.max_hp));
        }
        range(m.armor_class, 1, 30, "armorClass", &mut push);
        range(m.speed, 1, 50, "speed", &mut push);
        range(m.move_range_cells, 0, 24, "moveRangeCells", &mut push);
        range(m.attack_range_cells, 1, 24, "attackRangeCells", &mut push);
        if m.xp_reward < 0 {
            push(format!("xpReward {} must be >= 0", m.xp_reward));
        }
        if m.job_points_reward < 0 {
            push(format!(
                "jobPointsReward {} must be >= 0",
                m.job_points_reward
            ));
        }
    }

    if let Some(cycle) = find_job_graph_cycle(jobs) {
        issues.push(issue(
            "job graph",
            format!("job unlock cycle: {}", cycle.join(" -> ")),
        ));
    }
    issues
}

fn source(kind: &str, id: &str) -> String {
    format!(
        "{kind}:{}",
        if id.trim().is_empty() { "<blank>" } else { id }
    )
}

fn common(schema_version: i32, id: &str, display_name: &str, push: &mut impl FnMut(String)) {
    if schema_version != 1 {
        push(format!("schemaVersion {schema_version} must be 1"));
    }
    if id.trim().is_empty() {
        push("id must not be blank".into());
    } else if !id
        .bytes()
        .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
    {
        push(format!("id '{id}' must be lowercase and match [a-z0-9_]+"));
    }
    if display_name.trim().is_empty() {
        push("displayName must not be blank".into());
    }
}

fn range(value: i32, lo: i32, hi: i32, field: &str, push: &mut impl FnMut(String)) {
    if !(lo..=hi).contains(&value) {
        push(format!("{field} {value} out of range {lo}..{hi}"));
    }
}

fn ability_key(key: &str, field: &str, push: &mut impl FnMut(String)) {
    if !ABILITY_KEYS.contains(&key) {
        push(format!("{field} '{key}' is not one of {ABILITY_KEYS:?}"));
    }
}

fn dice(text: &str, field: &str, single_die: bool, push: &mut impl FnMut(String)) {
    match dice_count(text) {
        None => push(format!("{field} '{text}' is not valid dice notation")),
        Some(n) if single_die && n != 1 => push(format!(
            "{field} '{text}' must have exactly 1 die (count == 1)"
        )),
        Some(_) => {}
    }
}

/// The die count of `NdS[+-M]`, or 0 for a bare integer; None if neither.
/// Same grammar as Kotlin `DiceExpr.PATTERN`; numbers must fit an i32.
fn dice_count(text: &str) -> Option<i32> {
    let ws = |c: char| c.is_ascii_whitespace();
    let t = text.trim_matches(ws);
    let digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    let Some((count, rest)) = t.split_once('d') else {
        let n = t.strip_prefix('-').unwrap_or(t);
        return digits(n).then_some(0);
    };
    let sides_len = rest.bytes().take_while(u8::is_ascii_digit).count();
    let (sides, modifier) = rest.split_at(sides_len);
    let modifier = modifier.trim_start_matches(ws);
    if !digits(count) || !digits(sides) {
        return None;
    }
    if !modifier.is_empty() {
        let m = modifier.strip_prefix(['+', '-'])?.trim_start_matches(ws);
        if !digits(m) {
            return None;
        }
        m.parse::<i32>().ok()?;
    }
    let sides: i32 = sides.parse().ok()?;
    let count: i32 = count.parse().ok()?;
    // Zero-sided dice would panic in combat's next_int(0).
    (sides > 0 || count == 0).then_some(count)
}

/// A path of job ids with the first repeated last, or None when the graph of
/// edges `prerequisite -> job` and `job -> unlock` is acyclic.
pub fn find_job_graph_cycle(jobs: &[Job]) -> Option<Vec<String>> {
    let mut edges: HashMap<&str, Vec<&str>> =
        jobs.iter().map(|j| (j.id.as_str(), Vec::new())).collect();
    for job in jobs {
        for p in &job.prerequisites {
            if let Some(e) = edges.get_mut(p.as_str()) {
                e.push(&job.id);
            }
        }
        for u in &job.unlocks {
            if edges.contains_key(u.as_str()) {
                edges.get_mut(job.id.as_str()).unwrap().push(u);
            }
        }
    }

    fn dfs<'a>(
        node: &'a str,
        edges: &HashMap<&'a str, Vec<&'a str>>,
        visited: &mut HashSet<&'a str>,
        stack: &mut Vec<&'a str>,
    ) -> Option<Vec<String>> {
        visited.insert(node);
        stack.push(node);
        for &next in &edges[node] {
            if let Some(start) = stack.iter().position(|&n| n == next) {
                let mut path: Vec<String> = stack[start..].iter().map(|s| s.to_string()).collect();
                path.push(next.to_string());
                return Some(path);
            }
            if !visited.contains(next)
                && let Some(found) = dfs(next, edges, visited, stack)
            {
                return Some(found);
            }
        }
        stack.pop();
        None
    }

    let mut visited = HashSet::new();
    for job in jobs {
        if !visited.contains(job.id.as_str())
            && let Some(found) = dfs(&job.id, &edges, &mut visited, &mut Vec::new())
        {
            return Some(found);
        }
    }
    None
}

#[cfg(test)]
mod tests;
