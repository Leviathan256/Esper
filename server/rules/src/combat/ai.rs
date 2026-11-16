//! The one scripted unit brain (port of the Kotlin `SimpleAi`).

use super::{CombatAction, CombatEngine, CombatUnit};

/// Attack the lowest-HP enemy in range (tie: lowest id); else move to the
/// reachable cell nearest any living enemy (tie: lowest `(q, r)`), waiting if
/// that is where it stands or no enemy lives. Always legal.
pub fn choose_action(engine: &CombatEngine, actor: &CombatUnit) -> CombatAction {
    if let Some(target) = engine
        .attackable_targets(&actor.id)
        .into_iter()
        .min_by_key(|t| (t.current_hp, &t.id))
    {
        return CombatAction::Attack {
            target_id: target.id.clone(),
        };
    }
    let enemies: Vec<_> = engine
        .units()
        .iter()
        .filter(|u| u.alive() && u.player_controlled != actor.player_controlled)
        .map(|u| u.position)
        .collect();
    if enemies.is_empty() {
        return CombatAction::Wait;
    }
    let best = engine
        .legal_moves(&actor.id)
        .into_iter()
        .min_by_key(|cell| {
            let nearest = enemies.iter().map(|e| cell.distance_to(*e)).min();
            (nearest, cell.q, cell.r)
        })
        .expect("legal moves include the actor's own cell");
    if best == actor.position {
        CombatAction::Wait
    } else {
        CombatAction::Move { to: best }
    }
}
