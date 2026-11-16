//! Randomness, dice and the d20 roll (port of the Kotlin `SeededRandom`,
//! `DiceExpr` and `CombatMath`).

use std::fmt;

use serde::{Deserialize, Serialize};

/// `java.util.Random`, bit for bit: the Kotlin engine's `SeededRandom`, so a
/// seed replays the same fight in both.
#[derive(Debug, Clone)]
pub struct JavaRandom {
    seed: i64,
}

const MULTIPLIER: i64 = 0x5DEE_CE66D;
const MASK: i64 = (1 << 48) - 1;

impl JavaRandom {
    pub fn new(seed: i64) -> Self {
        Self {
            seed: (seed ^ MULTIPLIER) & MASK,
        }
    }

    fn next(&mut self, bits: u32) -> i32 {
        self.seed = (self.seed.wrapping_mul(MULTIPLIER).wrapping_add(0xB)) & MASK;
        (self.seed >> (48 - bits)) as i32
    }

    /// Uniform in `0..bound`. Panics unless `bound > 0`, as Java throws.
    pub fn next_int(&mut self, bound: i32) -> i32 {
        assert!(bound > 0, "bound must be positive");
        let mut r = self.next(31);
        let m = bound - 1;
        if bound & m == 0 {
            return ((i64::from(bound) * i64::from(r)) >> 31) as i32;
        }
        let mut u = r;
        loop {
            r = u % bound;
            // Java's int overflow test, rejecting the biased tail.
            if u.wrapping_sub(r).wrapping_add(m) >= 0 {
                return r;
            }
            u = self.next(31);
        }
    }
}

/// Dice notation such as `1d8+2`; a bare integer is zero dice. Serialised as
/// its notation.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(try_from = "String", into = "String")]
pub struct DiceExpr {
    pub count: i32,
    pub sides: i32,
    pub modifier: i32,
}

impl DiceExpr {
    /// Kotlin `DiceExpr.parseOrNull`: `\s*NdS\s*([+-]\s*M)?\s*` or `\s*[+-]?M\s*`,
    /// every number fitting an i32.
    pub fn parse(text: &str) -> Option<DiceExpr> {
        // Java regex `\s`.
        let ws = |c: char| matches!(c, ' ' | '\t' | '\n' | '\x0B' | '\x0C' | '\r');
        let digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
        let t = text.trim_matches(ws);
        let Some((count, rest)) = t.split_once('d') else {
            let n = t.strip_prefix(['+', '-']).unwrap_or(t);
            return digits(n)
                .then(|| t.parse().ok())
                .flatten()
                .map(|modifier| DiceExpr {
                    count: 0,
                    sides: 0,
                    modifier,
                });
        };
        let sides_len = rest.bytes().take_while(u8::is_ascii_digit).count();
        let (sides, tail) = rest.split_at(sides_len);
        if !digits(count) || !digits(sides) {
            return None;
        }
        let tail = tail.trim_start_matches(ws);
        let modifier = if tail.is_empty() {
            0
        } else {
            let unsigned = tail.strip_prefix(['+', '-'])?;
            let m = unsigned.trim_start_matches(ws);
            // Kotlin strips only spaces before toIntOrNull, so other whitespace fails.
            if !digits(m) || unsigned[..unsigned.len() - m.len()].contains(|c| c != ' ') {
                return None;
            }
            format!("{}{m}", &tail[..1]).parse().ok()?
        };
        Some(DiceExpr {
            count: count.parse().ok()?,
            sides: sides.parse().ok()?,
            modifier,
        })
    }

    pub fn roll(&self, rng: &mut JavaRandom) -> i32 {
        self.roll_dice(self.count, rng)
    }

    /// Critical: the dice count doubles, the modifier does not.
    pub fn roll_critical(&self, rng: &mut JavaRandom) -> i32 {
        self.roll_dice(self.count * 2, rng)
    }

    fn roll_dice(&self, count: i32, rng: &mut JavaRandom) -> i32 {
        (0..count)
            .map(|_| rng.next_int(self.sides) + 1)
            .sum::<i32>()
            + self.modifier
    }
}

impl fmt::Display for DiceExpr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.count == 0 && self.sides == 0 {
            return write!(f, "{}", self.modifier);
        }
        write!(f, "{}d{}", self.count, self.sides)?;
        match self.modifier {
            0 => Ok(()),
            m if m > 0 => write!(f, "+{m}"),
            m => write!(f, "{m}"),
        }
    }
}

impl TryFrom<String> for DiceExpr {
    type Error = String;
    fn try_from(text: String) -> Result<Self, String> {
        DiceExpr::parse(&text).ok_or_else(|| format!("not a valid dice expression: \"{text}\""))
    }
}

impl From<DiceExpr> for String {
    fn from(d: DiceExpr) -> String {
        d.to_string()
    }
}

/// One d20 attack roll, kept so the client can show the dice.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct AttackRoll {
    pub d20: i32,
    pub total: i32,
    pub hit: bool,
    pub critical: bool,
    pub fumble: bool,
}

/// `d20 + attack_bonus` vs AC; a natural 20 always hits, a natural 1 always misses.
pub fn roll_attack(attack_bonus: i32, target_armor_class: i32, rng: &mut JavaRandom) -> AttackRoll {
    let d20 = rng.next_int(20) + 1;
    let total = d20 + attack_bonus;
    let (critical, fumble) = (d20 == 20, d20 == 1);
    AttackRoll {
        d20,
        total,
        hit: !fumble && (critical || total >= target_armor_class),
        critical,
        fumble,
    }
}

/// Never below 0.
pub fn roll_damage(damage: &DiceExpr, critical: bool, rng: &mut JavaRandom) -> i32 {
    let raw = if critical {
        damage.roll_critical(rng)
    } else {
        damage.roll(rng)
    };
    raw.max(0)
}
