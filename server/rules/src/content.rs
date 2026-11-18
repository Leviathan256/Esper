//! Loads and validates `content/` (docs/SERVER.md §11). Mirrors the Kotlin
//! `ContentLoader` + `ContentValidator` rule for rule, except that unknown
//! fields are rejected here: the server is the authority, so a field it does
//! not know is a mistake, not a newer file. Takes text, never files.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;

use serde::Deserialize;

pub const ABILITY_KEYS: [&str; 6] = ["str", "dex", "con", "int", "wis", "cha"];

/// In the order the id hash indexes (look.schema.md, "Resolving a look").
pub const HUES: [&str; 8] = [
    "moss", "jade", "slate", "steel", "violet", "crimson", "ember", "bone",
];
pub const TONES: [&str; 5] = ["hue", "shade", "paper", "ink", "accent"];

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
    #[serde(default)]
    pub look: Option<Look>,
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
    #[serde(default)]
    pub look: Option<Look>,
}

/// How a unit is drawn (content/schema/look.schema.md). Every field is
/// optional; the server checks its structure and never judges the drawing.
#[derive(Debug, Clone, PartialEq, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Look {
    #[serde(default)]
    pub glyph: Option<String>,
    #[serde(default)]
    pub hue: Option<String>,
    #[serde(default)]
    pub details: Vec<LookDetail>,
}

/// One patch of colour on a unit's plate, clipped to its glyph.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct LookDetail {
    pub tone: String,
    pub d: String,
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
        look(&job.look, &mut push);
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
        look(&m.look, &mut push);
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

fn look(look: &Option<Look>, push: &mut impl FnMut(String)) {
    let Some(look) = look else { return };
    if let Some(glyph) = &look.glyph {
        path_field(glyph, "look.glyph", true, 40, push);
    }
    if look.details.len() > 6 {
        push(format!(
            "look has {} details, at most 6",
            look.details.len()
        ));
    }
    for (i, d) in look.details.iter().enumerate() {
        path_field(&d.d, &format!("look.details[{i}].d"), false, 16, push);
    }
    if let Some(hue) = &look.hue
        && !HUES.contains(&hue.as_str())
    {
        push(format!("look.hue '{hue}' is not one of {HUES:?}"));
    }
    for (i, d) in look.details.iter().enumerate() {
        if !TONES.contains(&d.tone.as_str()) {
            push(format!(
                "look.details[{i}].tone '{}' is not one of {TONES:?}",
                d.tone
            ));
        }
    }
}

fn path_field(
    d: &str,
    field: &str,
    cut_corners: bool,
    max_segments: usize,
    push: &mut impl FnMut(String),
) {
    match parse_path(d, cut_corners) {
        Err(why) => push(format!("{field}: {why}")),
        Ok((segments, subpaths)) => {
            if segments > max_segments {
                push(format!(
                    "{field} has {segments} segments, at most {max_segments}"
                ));
            }
            if subpaths > 6 {
                push(format!("{field} has {subpaths} subpaths, at most 6"));
            }
        }
    }
}

/// Parses a look path (look.schema.md, "Path grammar") and returns its
/// `(segments, subpaths)`, or why it is rejected. With `cut_corners`, every
/// point must also stay out of the box's four cut corners.
pub fn parse_path(d: &str, cut_corners: bool) -> Result<(usize, usize), String> {
    let mut toks = d.split(' ');
    let (mut segments, mut subpaths) = (0, 0);
    let mut tok = toks.next();
    while let Some(t) = tok {
        let first = t
            .strip_prefix('M')
            .ok_or(format!("expected M, found '{t}'"))?;
        point(first, toks.next(), cut_corners)?;
        loop {
            let t = toks.next().ok_or("a subpath does not end in Z")?;
            if t == "Z" {
                break;
            }
            let (letter, first) = t.split_at(t.chars().next().map_or(0, char::len_utf8));
            let points = match letter {
                "L" => 1,
                "C" => 3,
                _ => return Err(format!("expected L, C or Z, found '{t}'")),
            };
            point(first, toks.next(), cut_corners)?;
            for _ in 1..points {
                point(toks.next().unwrap_or(""), toks.next(), cut_corners)?;
            }
            segments += 1;
        }
        subpaths += 1;
        tok = toks.next();
    }
    if subpaths == 0 {
        return Err("empty path".into());
    }
    Ok((segments, subpaths))
}

/// Checks one point, `x` and then `y`, in hundredths of a unit so the corner
/// sums are exact.
fn point(x: &str, y: Option<&str>, cut_corners: bool) -> Result<(), String> {
    let (x, y) = (number(x)?, number(y.unwrap_or(""))?);
    let far = |v: u32| 2400 - v;
    if cut_corners
        && [x + y, far(x) + y, x + far(y), far(x) + far(y)]
            .iter()
            .any(|&s| s < 400)
    {
        return Err(format!(
            "point ({}, {}) is inside a cut corner",
            x as f64 / 100.0,
            y as f64 / 100.0
        ));
    }
    Ok(())
}

/// 1 or 2 digits, optionally "." and 1 or 2 digits, 0 to 24; in hundredths.
fn number(s: &str) -> Result<u32, String> {
    let bad = || format!("'{s}' is not a number from 0 to 24 with at most 2 decimals");
    let (int, frac) = s.split_once('.').unwrap_or((s, "00"));
    let digits = |p: &str| (1..=2).contains(&p.len()) && p.bytes().all(|b| b.is_ascii_digit());
    if !digits(int) || !digits(frac) {
        return Err(bad());
    }
    let frac: u32 = format!("{frac:0<2}").parse().map_err(|_| bad())?;
    let v = int.parse::<u32>().map_err(|_| bad())? * 100 + frac;
    if v > 2400 { Err(bad()) } else { Ok(v) }
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
pub(crate) mod tests;
