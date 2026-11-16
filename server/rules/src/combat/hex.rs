//! Axial hex maths, pointy-top (port of the Kotlin `HexCoord` + `HexGrid`).
//! Iteration orders match Kotlin's: ring placement and recordings depend on them.

use std::collections::{HashMap, HashSet, VecDeque};
use std::fmt;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct HexCoord {
    pub q: i32,
    pub r: i32,
}

pub const ORIGIN: HexCoord = HexCoord { q: 0, r: 0 };

/// Fixed order: ring placement and tie-breaks rely on it.
pub const DIRECTIONS: [HexCoord; 6] = [
    HexCoord { q: 1, r: 0 },
    HexCoord { q: 1, r: -1 },
    HexCoord { q: 0, r: -1 },
    HexCoord { q: -1, r: 0 },
    HexCoord { q: -1, r: 1 },
    HexCoord { q: 0, r: 1 },
];

impl HexCoord {
    pub const fn new(q: i32, r: i32) -> Self {
        Self { q, r }
    }

    pub fn distance_to(self, other: HexCoord) -> i32 {
        let (dq, dr) = (other.q - self.q, other.r - self.r);
        (dq.abs() + dr.abs() + (dq + dr).abs()) / 2
    }

    pub fn neighbors(self) -> impl Iterator<Item = HexCoord> {
        DIRECTIONS
            .into_iter()
            .map(move |d| HexCoord::new(self.q + d.q, self.r + d.r))
    }
}

/// Kotlin's data-class `toString`, which rejection reasons quote.
impl fmt::Display for HexCoord {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "HexCoord(q={}, r={})", self.q, self.r)
    }
}

/// Every cell within `radius` of `center`, `3n(n+1)+1` of them.
pub fn cells_within_radius(center: HexCoord, radius: i32) -> Vec<HexCoord> {
    let mut cells = Vec::new();
    for dq in -radius..=radius {
        for dr in (-radius).max(-dq - radius)..=radius.min(-dq + radius) {
            cells.push(HexCoord::new(center.q + dq, center.r + dr));
        }
    }
    cells
}

/// The cells at exactly `radius` from `center`.
pub fn ring(center: HexCoord, radius: i32) -> Vec<HexCoord> {
    cells_within_radius(center, radius)
        .into_iter()
        .filter(|c| c.distance_to(center) == radius)
        .collect()
}

/// BFS, so a unit cannot hop a wall of bodies. Includes `from`; in visit order.
pub fn reachable_cells(
    from: HexCoord,
    move_range: i32,
    allowed: &HashSet<HexCoord>,
    blocked: &HashSet<HexCoord>,
) -> Vec<HexCoord> {
    let mut distance = HashMap::from([(from, 0)]);
    let mut order = vec![from];
    let mut frontier = VecDeque::from([from]);
    while let Some(current) = frontier.pop_front() {
        let d = distance[&current];
        if d >= move_range {
            continue;
        }
        for n in current.neighbors() {
            if distance.contains_key(&n) || !allowed.contains(&n) || blocked.contains(&n) {
                continue;
            }
            distance.insert(n, d + 1);
            order.push(n);
            frontier.push_back(n);
        }
    }
    order
}
