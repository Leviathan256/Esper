# Encounter hazards come from our own Overpass, one region, asked through the Esper server

Before seeding an Encounter the app needs the roads, railways, water and private land around the player, and without them it seeds nothing. It asked the public Overpass API, but the public instances are saturated: on 2026-11-17 overpass-api.de, overpass.private.coffee, overpass.kumi.systems and maps.mail.ru each answered at capacity, answered 502 or 504, or held even a trivial `node(1)` query past 25 s, so no Encounter spawned. We now run Overpass in the k3s cluster, imported from one Geofabrik regional extract and updated daily, and the app asks the Esper server (`GET /v1/hazards`, `docs/SERVER.md` §5), never a public instance. The server builds the query and refuses points outside the region. The region is a deploy-time setting kept out of git: the repo is public, and the region says roughly where the players are.

## Considered Options

- **Retry with backoff across the public instances**: rejected. It fails exactly when every instance is saturated at once, as they were.
- **A whole-country extract**: rejected. Too big for this host's 2 CPUs and its disk.
- **Exposing our Overpass publicly for the app to query**: rejected. Anyone could run any query on a 2 CPU host. Behind the server, only signed-in players reach it, with one fixed query and at most 4 in flight.

## Consequences

- Encounters spawn only inside the coverage region, at least 100 m from its edge so the 40 m query never reaches past the imported data. Elsewhere the server answers `404 outside_coverage` and the app says "Encounters aren't available in this area yet." Missing data would read as "no hazards" and seed an Encounter on a road, so this check fails closed.
- The first import takes many minutes, longer for a bigger region. Encounters stay off until it finishes.
- Changing the region means importing the map again and restarting the server, which reads the region's `.poly` only at startup. `deploy/host-setup.sh --overpass-region` does all of it: Overpass's init container re-imports a database built for another region, and the script restarts the server before Overpass returns.
- The data is as fresh as Geofabrik's daily diffs: up to about a day behind OpenStreetMap.
- Overpass shares the 2 vCPU / 8 GB host and its disk with Postgres and the server (memory limit 3 Gi, a 20 Gi volume).
- Only the hazard data moved. Map tiles still come from the OSM Foundation's servers (`docs/GAME_DESIGN.md`, "Tile source").
- Self-hosting lets us set the Overpass area rules. The stock rules only cover named multipolygons, so the middle of an unnamed lake, or of any hazard polygon mapped as a closed way, read as safe even on the public instances; `overpass.yaml` adds areas for every hazard tag the engine classifies.
