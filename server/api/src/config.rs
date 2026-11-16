//! Everything the server reads from its environment (docs/SERVER.md §8).

use std::path::PathBuf;

use webauthn_rs::prelude::Url;

pub struct Config {
    pub bind: String,
    pub db_url: String,
    pub rp_id: String,
    /// Android app origins, e.g. `android:apk-key-hash:<b64url>`. Never empty.
    pub rp_origins: Vec<Url>,
    pub content_dir: String,
    pub assetlinks_file: PathBuf,
}

impl Config {
    pub fn from_env() -> Result<Self, String> {
        Self::from_lookup(|name| std::env::var(name).ok())
    }

    /// Blank values count as unset.
    fn from_lookup(env: impl Fn(&str) -> Option<String>) -> Result<Self, String> {
        let get = |name: &str| env(name).filter(|v| !v.trim().is_empty());
        let rp_origins = parse_origins(&get("ESPER_RP_ORIGINS").unwrap_or_default())?;
        // Fail fast: with no origin every ceremony fails on the phone as an opaque passkey_rejected.
        if rp_origins.is_empty() {
            return Err("ESPER_RP_ORIGINS is empty: set it to the 'passkey origin' row of a build-apk run summary".into());
        }
        Ok(Self {
            bind: get("ESPER_BIND").unwrap_or_else(|| "0.0.0.0:8080".into()),
            db_url: get("ESPER_DB_URL").ok_or("ESPER_DB_URL is required")?,
            rp_id: get("ESPER_RP_ID").unwrap_or_else(|| "esper.media".into()),
            rp_origins,
            content_dir: get("ESPER_CONTENT_DIR").unwrap_or_else(|| "content".into()),
            assetlinks_file: get("ESPER_ASSETLINKS_FILE")
                .unwrap_or_else(|| "/etc/esper/assetlinks.json".into())
                .into(),
        })
    }
}

/// Comma list, trimmed, blanks dropped; each entry must parse as a URL.
pub fn parse_origins(raw: &str) -> Result<Vec<Url>, String> {
    raw.split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| Url::parse(s).map_err(|e| format!("ESPER_RP_ORIGINS: {s:?}: {e}")))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config(vars: &[(&str, &str)]) -> Result<Config, String> {
        Config::from_lookup(|name| {
            vars.iter()
                .find(|(k, _)| *k == name)
                .map(|(_, v)| v.to_string())
        })
    }

    #[test]
    fn origins_split_on_commas_trim_and_drop_blanks() {
        let origins =
            parse_origins(" android:apk-key-hash:abc , ,android:apk-key-hash:def,").unwrap();
        let origins: Vec<&str> = origins.iter().map(Url::as_str).collect();
        assert_eq!(
            origins,
            ["android:apk-key-hash:abc", "android:apk-key-hash:def"]
        );
    }

    #[test]
    fn empty_or_missing_origins_stop_startup() {
        let db = ("ESPER_DB_URL", "postgres://x");
        for origins in [None, Some(""), Some(" , ")] {
            let vars: Vec<_> = [Some(db), origins.map(|o| ("ESPER_RP_ORIGINS", o))]
                .into_iter()
                .flatten()
                .collect();
            let err = config(&vars).err().unwrap();
            assert!(err.contains("ESPER_RP_ORIGINS"), "{err}");
        }
    }

    #[test]
    fn rp_id_defaults_to_esper_media() {
        let c = config(&[
            ("ESPER_DB_URL", "postgres://x"),
            ("ESPER_RP_ORIGINS", "android:apk-key-hash:abc"),
        ])
        .unwrap();
        assert_eq!(c.rp_id, "esper.media");
        assert_eq!(c.bind, "0.0.0.0:8080");
    }
}
