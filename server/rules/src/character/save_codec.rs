//! Port of the Kotlin `SaveCodec` + `SaveMigrations`: reads and writes exactly
//! what the app does. The golden fixtures in `content/fixtures/save/` are read
//! by both test suites, so the two codecs cannot drift silently (ADR-0002).

use serde::de::Error as _;
use serde_json::Value;

use super::CharacterState;

pub fn encode(state: &CharacterState) -> String {
    serde_json::to_string(state).expect("a CharacterState always serializes")
}

/// Runs `migrate` first. A syntax or EOF error means the text is not JSON;
/// any other error means it is JSON but not a save.
pub fn decode(text: &str) -> Result<CharacterState, serde_json::Error> {
    serde_json::from_value(migrate(serde_json::from_str(text)?)?)
}

/// The schemaVersion-keyed migration chain. A schema change ships with the
/// step that carries existing characters across.
pub fn migrate(mut save: Value) -> Result<Value, serde_json::Error> {
    let obj = save
        .as_object_mut()
        .ok_or_else(|| serde_json::Error::custom("a save is a JSON object"))?;
    let version = match obj.get("schemaVersion") {
        None | Some(Value::Null) => 0,
        Some(v) => v
            .as_i64()
            .ok_or_else(|| serde_json::Error::custom("schemaVersion is not an integer"))?,
    };

    // v0 -> v1: no shape change, just stamp the version.
    if version < 1 {
        obj.insert("schemaVersion".into(), 1.into());
    }

    Ok(save)
}
