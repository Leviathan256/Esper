//! `GET /v1/hazards` (issue #37): roads, railways, water and private land
//! around a point, from the self-hosted Overpass (deploy/k8s/base/overpass.yaml).
//! The server builds the query itself and refuses points the imported region
//! does not cover, because missing data would read as "no hazards" and seed an
//! encounter on a road. Every failure is a 503, never a partial result. The
//! position is never logged.

use std::{
    collections::HashSet,
    path::Path,
    sync::{Arc, Mutex, PoisonError},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use axum::{
    body::Bytes,
    extract::{Query, State, rejection::QueryRejection},
    http::{
        Request, Uri,
        header::{CONTENT_TYPE, USER_AGENT},
    },
    response::{IntoResponse, Response},
};
use http_body_util::{BodyExt, Full};
use hyper_util::{
    client::legacy::{Client, connect::HttpConnector},
    rt::TokioExecutor,
};
use serde::Deserialize;
use tokio::sync::Semaphore;
use webauthn_rs::prelude::Uuid;

use crate::{auth::sessions::Session, error::ApiError};

/// Same radius the app asked the public Overpass for.
const RADIUS_M: u32 = 40;
/// Keeps the 40 m query inside the region outline; it does not make the data there complete.
// shortcut: ways with no node inside the outline (a long segment cutting a corner of it) are absent from the extract, so a point near such a corner can miss that way; widen the margin or buffer the .poly if it matters.
const MARGIN_M: f64 = 100.0;
pub const MAX_IN_FLIGHT: usize = 4;
/// Slot wait plus the Overpass timeout stay under the app's 20 s read timeout
/// (not counting the session lookup, bounded by the pool's 3 s acquire timeout;
/// if that pushes past 20 s the app gives up and still seeds nothing).
const SLOT_WAIT: Duration = Duration::from_secs(2);
/// The query's own `[timeout:15]` plus a second for the round trip.
const UPSTREAM_TIMEOUT: Duration = Duration::from_secs(16);
const _: () = assert!(SLOT_WAIT.as_secs() + UPSTREAM_TIMEOUT.as_secs() < 20);
/// Older map data fails closed: a stalled diff updater must not pass for today's roads.
const MAX_AGE_S: i64 = 7 * 86_400;
/// The engine's sphere (LocalTangentPlane.EARTH_RADIUS_METRES).
const METRES_PER_DEGREE: f64 = 6_371_008.8 * std::f64::consts::PI / 180.0;

/// Overpass tag filters, copied from the engine's `OverpassHazards.FILTERS`.
const FILTERS: [&str; 7] = [
    "[highway]",
    "[railway]",
    "[waterway]",
    r#"[natural~"^(water|wetland|coastline)$"]"#,
    r#"[landuse~"^(reservoir|basin|military|railway)$"]"#,
    "[building]",
    r#"[access~"^(private|no)$"]"#,
];

/// Port of the engine's `OverpassHazards.query` with the radius fixed.
pub fn query(lat: f64, lon: f64) -> String {
    let around = format!("(around:{RADIUS_M},{lat},{lon})");
    let mut q = format!("[out:json][timeout:15];\nis_in({lat},{lon})->.inside;\n(\n");
    for filter in FILTERS {
        for set in [
            format!("way{around}"),
            format!("rel{around}"),
            "way(pivot.inside)".into(),
            "rel(pivot.inside)".into(),
        ] {
            q += &format!("  {set}{filter};\n");
        }
    }
    q + ");\nout geom;\n"
}

struct Ring {
    hole: bool,
    /// `(lon, lat)` in degrees, as the file has them.
    points: Vec<(f64, f64)>,
}

/// The imported region's outline, from Geofabrik's `.poly`.
pub struct Region {
    rings: Vec<Ring>,
}

impl Region {
    /// None when the file does not exist: the Secret is optional, and the route then answers 503.
    pub fn load(path: &Path) -> Result<Option<Self>, String> {
        match std::fs::read_to_string(path) {
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
            read => read
                .map_err(|e| e.to_string())
                .and_then(|text| Self::parse(&text))
                .map(Some)
                .map_err(|e| format!("{}: {e}", path.display())),
        }
    }

    /// Line 1 names the file; then sections of a name (`!` marks a hole),
    /// `lon lat` lines and `END`; a final `END` closes the file.
    pub fn parse(text: &str) -> Result<Self, String> {
        let mut lines = text.lines().map(str::trim).filter(|l| !l.is_empty());
        lines.next().ok_or("empty file")?;
        let mut rings = Vec::new();
        loop {
            let name = lines.next().ok_or("no final END")?;
            if name == "END" {
                break;
            }
            // Sections are named by position: errors must not echo file contents.
            let section = rings.len() + 1;
            let mut points = Vec::new();
            loop {
                let line = lines
                    .next()
                    .ok_or_else(|| format!("section {section}: no END"))?;
                if line == "END" {
                    break;
                }
                let n = points.len() + 1;
                let p = point(line).ok_or_else(|| format!("section {section}: bad point {n}"))?;
                points.push(p);
            }
            if points.len() < 3 {
                return Err(format!("section {section}: fewer than 3 points"));
            }
            let hole = name.starts_with('!');
            rings.push(Ring { hole, points });
        }
        if lines.next().is_some() {
            return Err("text after the final END".into());
        }
        if rings.iter().all(|r| r.hole) {
            return Err("no outer ring".into());
        }
        Ok(Self { rings })
    }

    /// Inside an outer ring, inside no hole, and at least 100 m from every edge.
    // shortcut: no antimeridian handling; a region crossing ±180° reads as uncovered there (fails closed).
    pub fn covers(&self, lat: f64, lon: f64) -> bool {
        let east = METRES_PER_DEGREE * lat.to_radians().cos();
        // Local equirectangular metres with the point at the origin.
        let local = |&(x, y): &(f64, f64)| ((x - lon) * east, (y - lat) * METRES_PER_DEGREE);
        let (mut outer, mut hole) = (false, false);
        for ring in &self.rings {
            let next = ring.points.iter().cycle().skip(1);
            let mut inside = false;
            for (a, b) in ring.points.iter().map(local).zip(next.map(local)) {
                if distance_to_origin(a, b) < MARGIN_M {
                    return false;
                }
                // Even-odd rule on the ray from the point towards +x.
                if (a.1 > 0.0) != (b.1 > 0.0) && a.0 - a.1 * (b.0 - a.0) / (b.1 - a.1) > 0.0 {
                    inside = !inside;
                }
            }
            if ring.hole {
                hole |= inside;
            } else {
                outer |= inside;
            }
        }
        outer && !hole
    }
}

/// `lon lat`, both finite; scientific notation allowed.
fn point(line: &str) -> Option<(f64, f64)> {
    let mut nums = line
        .split_whitespace()
        .map(|n| n.parse::<f64>().ok().filter(|v| v.is_finite()));
    let (lon, lat) = (nums.next()??, nums.next()??);
    nums.next().is_none().then_some((lon, lat))
}

fn distance_to_origin(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let len2 = dx * dx + dy * dy;
    let t = if len2 == 0.0 {
        0.0
    } else {
        (-(a.0 * dx + a.1 * dy) / len2).clamp(0.0, 1.0)
    };
    (a.0 + t * dx).hypot(a.1 + t * dy)
}

pub struct Hazards {
    /// None: not configured, every request is 503.
    upstream: Option<(Uri, Region)>,
    client: Client<HttpConnector, Full<Bytes>>,
    pub(crate) slots: Arc<Semaphore>,
    /// Accounts with a query in flight: one each, so one client cannot take every slot.
    busy: Mutex<HashSet<Uuid>>,
    /// Fields so tests can shorten them.
    pub slot_wait: Duration,
    /// Whole Overpass round trip.
    pub timeout: Duration,
}

/// Marks an account busy until dropped.
struct Busy(Arc<Hazards>, Uuid);

impl Drop for Busy {
    fn drop(&mut self) {
        // Only insert/remove run under this lock, so it cannot be poisoned; never panic in Drop anyway.
        let mut busy = self.0.busy.lock().unwrap_or_else(PoisonError::into_inner);
        busy.remove(&self.1);
    }
}

impl Hazards {
    pub fn new(upstream: Option<(Uri, Region)>) -> Self {
        Self {
            upstream,
            client: Client::builder(TokioExecutor::new()).build_http(),
            slots: Arc::new(Semaphore::new(MAX_IN_FLIGHT)),
            busy: Mutex::default(),
            slot_wait: SLOT_WAIT,
            timeout: UPSTREAM_TIMEOUT,
        }
    }

    /// The body unchanged, or why it cannot be trusted. Errors never carry the query.
    async fn fetch(&self, url: &Uri, ql: &str) -> Result<Bytes, String> {
        let form = form_urlencoded::Serializer::new(String::new())
            .append_pair("data", ql)
            .finish();
        let req = Request::post(url.clone())
            .header(CONTENT_TYPE, "application/x-www-form-urlencoded")
            .header(USER_AGENT, "esper-server")
            .body(Full::from(form))
            .map_err(|e| e.to_string())?;
        let res = self.client.request(req).await.map_err(|e| e.to_string())?;
        let status = res.status();
        let body = res.into_body().collect().await.map_err(|e| e.to_string())?;
        let body = body.to_bytes();
        if !status.is_success() {
            return Err(format!("HTTP {status}"));
        }
        let json: serde_json::Value =
            serde_json::from_slice(&body).map_err(|e| format!("not JSON: {e}"))?;
        // Overpass answers 200 with partial data on a timeout and flags it here.
        let remark = json.get("remark").and_then(|r| r.as_str()).unwrap_or("");
        if remark.to_lowercase().contains("error") {
            return Err("remark reports an error".into());
        }
        // The map data, and the areas that is_in (pivot.inside) relies on.
        let now = SystemTime::now().duration_since(UNIX_EPOCH);
        let now = now.map_or(0, |d| d.as_secs() as i64);
        for key in ["/osm3s/timestamp_osm_base", "/osm3s/timestamp_areas_base"] {
            let stamp = json.pointer(key).and_then(|t| unix_seconds(t.as_str()?));
            if now - stamp.ok_or("no timestamp")? > MAX_AGE_S {
                return Err("data is stale".into());
            }
        }
        Ok(body)
    }
}

/// Seconds since the Unix epoch of Overpass's `2026-11-16T21:20:42Z`; None for any other shape.
fn unix_seconds(ts: &str) -> Option<i64> {
    let shape = "0000-00-00T00:00:00Z";
    let fits = |(c, s): (u8, u8)| {
        if s == b'0' {
            c.is_ascii_digit()
        } else {
            c == s
        }
    };
    if ts.len() != shape.len() || !ts.bytes().zip(shape.bytes()).all(fits) {
        return None;
    }
    // All ASCII now, so slicing cannot split a character.
    let n = |at: usize, len: usize| ts[at..at + len].parse::<i64>().ok();
    let (y, mo, d) = (n(0, 4)?, n(5, 2)?, n(8, 2)?);
    let (h, mi, s) = (n(11, 2)?, n(14, 2)?, n(17, 2)?);
    let leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
    let month_days = [
        31,
        28 + i64::from(leap),
        31,
        30,
        31,
        30,
        31,
        31,
        30,
        31,
        30,
        31,
    ];
    if !(1..=12).contains(&mo) || d < 1 || d > month_days[mo as usize - 1] {
        return None;
    }
    if h > 23 || mi > 59 || s > 59 {
        return None;
    }
    Some(days_from_civil(y, mo, d) * 86_400 + h * 3600 + mi * 60 + s)
}

/// Days since 1970-01-01, proleptic Gregorian (Howard Hinnant's `days_from_civil`).
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * ((m + 9) % 12) + 2) / 5 + d - 1;
    era * 146_097 + yoe * 365 + yoe / 4 - yoe / 100 + doy - 719_468
}

#[derive(Deserialize)]
pub struct Point {
    lat: f64,
    lon: f64,
}

/// `GET /v1/hazards?lat=&lon=`.
pub async fn get_hazards(
    State(hazards): State<Arc<Hazards>>,
    session: Session,
    point: Result<Query<Point>, QueryRejection>,
) -> Result<Response, ApiError> {
    let Query(Point { lat, lon }) = point.map_err(|_| ApiError::BAD_REQUEST)?;
    // Range checks reject NaN and infinities too, as in location::validate.
    if !((-90.0..=90.0).contains(&lat) && (-180.0..=180.0).contains(&lon)) {
        return Err(ApiError::INVALID_FIX);
    }
    let Some((url, region)) = &hazards.upstream else {
        return Err(ApiError::HAZARDS_UNAVAILABLE);
    };
    if !region.covers(lat, lon) {
        return Err(ApiError::OUTSIDE_COVERAGE);
    }
    let first = (hazards.busy.lock())
        .unwrap_or_else(PoisonError::into_inner)
        .insert(session.account_id);
    if !first {
        return Err(ApiError::RATE_LIMITED);
    }
    let busy = Busy(hazards.clone(), session.account_id);
    let free = tokio::time::timeout(hazards.slot_wait, hazards.slots.clone().acquire_owned());
    let Ok(Ok(slot)) = free.await else {
        tracing::warn!("overpass: no free slot in {:?}", hazards.slot_wait);
        return Err(ApiError::HAZARDS_UNAVAILABLE);
    };
    let (url, ql) = (url.clone(), query(lat, lon));
    // Its own task, so a client hanging up mid-query cannot free the slot while Overpass still works on it.
    let task = tokio::spawn(async move {
        let _held = (busy, slot);
        let fetched = tokio::time::timeout(hazards.timeout, hazards.fetch(&url, &ql)).await;
        fetched.unwrap_or_else(|_| Err("timed out".into()))
    });
    let fetched = task
        .await
        .unwrap_or_else(|_| Err("query task failed".into()));
    match fetched {
        Ok(body) => Ok(([(CONTENT_TYPE, "application/json")], body).into_response()),
        Err(e) => {
            tracing::warn!("overpass: {e}");
            Err(ApiError::HAZARDS_UNAVAILABLE)
        }
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// A 0.1° square with a 0.02° hole in the middle, the way Geofabrik writes it.
    pub const SQUARE: &str = "test_region
1
   8.600000E+00   4.710000E+01
   8.700000E+00   4.710000E+01
   8.700000E+00   4.720000E+01
   8.600000E+00   4.720000E+01
   8.600000E+00   4.710000E+01
END
!2
   8.640000E+00   4.714000E+01
   8.660000E+00   4.714000E+01
   8.660000E+00   4.716000E+01
   8.640000E+00   4.716000E+01
END
END
";

    fn square() -> Region {
        Region::parse(SQUARE).unwrap()
    }

    #[test]
    fn parses_sections_holes_and_scientific_notation() {
        let r = square();
        assert_eq!(r.rings.len(), 2);
        assert!(!r.rings[0].hole && r.rings[1].hole);
        assert_eq!(r.rings[0].points[1], (8.7, 47.1));
        assert_eq!(r.rings[1].points.len(), 4);
        assert!(
            Region::load(Path::new("/nonexistent/region.poly"))
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn rejects_malformed_files() {
        let ring = "1\n0 0\n1 0\n1 1\nEND\n";
        for bad in [
            String::new(),
            format!("x\n{ring}"),           // no final END
            "x\n1\n0 0\n1 0\n1 1\n".into(), // section without END
            format!("x\n{}END\n", ring.replace("1 0", "1 north")),
            format!("x\n{}END\n", ring.replace("1 0", "1 0 5")),
            format!("x\n{}END\n", ring.replace("1 0", "1 NaN")),
            "x\n1\n0 0\n1 1\nEND\nEND\n".into(), // two points
            format!("x\n!{ring}END\n"),          // only a hole
            format!("x\n{ring}END\nmore\n"),
        ] {
            assert!(Region::parse(&bad).is_err(), "{bad:?}");
        }
        assert!(Region::parse(&format!("x\n{ring}END\n")).is_ok());
        let secret = format!("x\n{}END\n", ring.replace("1 0", "1 north"));
        let err = Region::parse(&secret).err().unwrap();
        assert_eq!(err, "section 1: bad point 2");
        assert!(!err.contains("north"), "{err}");
        // A file that lost its header line: a coordinate lands where a section name goes.
        let headless = "1\n8.6123 47.1456\n8.7 47.1\n8.7 47.2\nEND\nEND\n";
        let err = Region::parse(headless).err().unwrap();
        assert!(!err.contains("8.6123") && !err.contains("47.1456"), "{err}");
    }

    /// `unix_seconds` backwards (Hinnant's `civil_from_days`).
    pub fn stamp_at(unix: i64) -> String {
        let (days, secs) = (unix.div_euclid(86_400), unix.rem_euclid(86_400));
        let z = days + 719_468;
        let era = z.div_euclid(146_097);
        let doe = z - era * 146_097;
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        let mp = (5 * doy + 2) / 153;
        let (d, m) = (
            doy - (153 * mp + 2) / 5 + 1,
            if mp < 10 { mp + 3 } else { mp - 9 },
        );
        let y = yoe + era * 400 + i64::from(m <= 2);
        let (h, mi, s) = (secs / 3600, secs / 60 % 60, secs % 60);
        format!("{y:04}-{m:02}-{d:02}T{h:02}:{mi:02}:{s:02}Z")
    }

    /// An Overpass answer whose data is `age_s` seconds old by the server clock.
    pub fn answer(age_s: i64) -> String {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap();
        let stamp = stamp_at(now.as_secs() as i64 - age_s);
        format!(
            r#"{{"version":0.6,"osm3s":{{"timestamp_osm_base":"{stamp}","timestamp_areas_base":"{stamp}"}},"remark":"","elements":[{{"type":"way","id":1}}]}}"#
        )
    }

    #[test]
    fn reads_overpass_timestamps() {
        assert_eq!(unix_seconds("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(unix_seconds("2026-11-16T21:20:42Z"), Some(1_794_864_042));
        // Leap years, the century rule included: 29 February exists, and 1 March follows it.
        assert_eq!(unix_seconds("2024-02-29T12:00:00Z"), Some(1_709_208_000));
        assert_eq!(unix_seconds("2024-03-01T00:00:00Z"), Some(1_709_251_200));
        // Days that do not exist in their month.
        for bad in [
            "2026-02-31T00:00:00Z",
            "2026-04-31T00:00:00Z",
            "2023-02-29T00:00:00Z",
            "1900-02-29T00:00:00Z",
        ] {
            assert_eq!(unix_seconds(bad), None, "{bad}");
        }
        assert!(unix_seconds("2000-02-29T00:00:00Z").is_some());
        assert_eq!(unix_seconds("2000-03-01T00:00:00Z"), Some(951_868_800));
        assert_eq!(unix_seconds("2100-03-01T00:00:00Z"), Some(4_107_542_400));
        for bad in [
            "",
            "yesterday",
            "2026-11-16",
            "2026-11-16T21:20:42",
            "2026-11-16 21:20:42Z",
            "2026-11-16T21:20:42+00:00",
            "2026-11-16T21:20:42.5Z",
            "+026-11-16T21:20:42Z",
            "2026-11-16T21:20:éZ",
            "2026-00-16T21:20:42Z",
            "2026-13-16T21:20:42Z",
            "2026-11-00T21:20:42Z",
            "2026-11-32T21:20:42Z",
            "2026-11-16T24:20:42Z",
            "2026-11-16T21:60:42Z",
            "2026-11-16T21:20:60Z",
        ] {
            assert_eq!(unix_seconds(bad), None, "{bad}");
        }
        // Every day for 150 years, at 12:34:56.
        for unix in (0..55_000).map(|day| day * 86_400 + 45_296) {
            assert_eq!(
                unix_seconds(&stamp_at(unix)),
                Some(unix),
                "{}",
                stamp_at(unix)
            );
        }
    }

    #[test]
    fn covers_inside_not_outside_and_not_in_a_hole() {
        let r = square();
        assert!(r.covers(47.12, 8.62));
        assert!(r.covers(47.18, 8.68));
        assert!(!r.covers(47.15, 8.65), "in the hole");
        assert!(!r.covers(47.05, 8.65), "south of the square");
        assert!(!r.covers(47.15, 8.75), "east of the square");
        assert!(!r.covers(-47.15, -8.65));
    }

    #[test]
    fn keeps_100_m_from_every_edge() {
        let r = square();
        let north = |m: f64| m / METRES_PER_DEGREE;
        let east = |m: f64| m / (METRES_PER_DEGREE * 47.12_f64.to_radians().cos());
        // South edge of the outer ring, from inside and outside.
        assert!(r.covers(47.1 + north(101.0), 8.62));
        assert!(!r.covers(47.1 + north(99.0), 8.62));
        assert!(!r.covers(47.1 - north(150.0), 8.62));
        // East edge: longitude metres shrink with latitude.
        assert!(r.covers(47.12, 8.7 - east(101.0)));
        assert!(!r.covers(47.12, 8.7 - east(99.0)));
        // Outside the hole but near its edge.
        assert!(r.covers(47.14 - north(101.0), 8.65));
        assert!(!r.covers(47.14 - north(99.0), 8.65));
    }

    #[test]
    fn query_matches_the_engine_with_a_40_m_radius() {
        let want = r#"[out:json][timeout:15];
is_in(51.5,-0.12)->.inside;
(
  way(around:40,51.5,-0.12)[highway];
  rel(around:40,51.5,-0.12)[highway];
  way(pivot.inside)[highway];
  rel(pivot.inside)[highway];
  way(around:40,51.5,-0.12)[railway];
  rel(around:40,51.5,-0.12)[railway];
  way(pivot.inside)[railway];
  rel(pivot.inside)[railway];
  way(around:40,51.5,-0.12)[waterway];
  rel(around:40,51.5,-0.12)[waterway];
  way(pivot.inside)[waterway];
  rel(pivot.inside)[waterway];
  way(around:40,51.5,-0.12)[natural~"^(water|wetland|coastline)$"];
  rel(around:40,51.5,-0.12)[natural~"^(water|wetland|coastline)$"];
  way(pivot.inside)[natural~"^(water|wetland|coastline)$"];
  rel(pivot.inside)[natural~"^(water|wetland|coastline)$"];
  way(around:40,51.5,-0.12)[landuse~"^(reservoir|basin|military|railway)$"];
  rel(around:40,51.5,-0.12)[landuse~"^(reservoir|basin|military|railway)$"];
  way(pivot.inside)[landuse~"^(reservoir|basin|military|railway)$"];
  rel(pivot.inside)[landuse~"^(reservoir|basin|military|railway)$"];
  way(around:40,51.5,-0.12)[building];
  rel(around:40,51.5,-0.12)[building];
  way(pivot.inside)[building];
  rel(pivot.inside)[building];
  way(around:40,51.5,-0.12)[access~"^(private|no)$"];
  rel(around:40,51.5,-0.12)[access~"^(private|no)$"];
  way(pivot.inside)[access~"^(private|no)$"];
  rel(pivot.inside)[access~"^(private|no)$"];
);
out geom;
"#;
        assert_eq!(query(51.5, -0.12), want);
    }
}
