# Otto setup - real map, floors, settings without a rebuild (contract)

Adds to `journeys.md`. Robot = OrionStar GreetingBot Nova named Otto, API id "nova".

## 1. The real map

- Robot: on start and whenever the current map changes, read the current map's floor plan with `ShareMemoryApi.getMapPgmPFD(mapName)` (RobotAPI_en.md "Read and Display Map PGM File"; release with `releaseGetMapPgmPFD()`), plus what is needed to place map coordinates on it (resolution in m per pixel, origin of pixel 0,0 in map meters; find it in the RoverMap / map data classes with javap, log what you found). Convert to a grayscale PNG (downscale to at most 2048 px on the long side, keep the scale factor in the resolution), and upload only when it changed (hash):
  `POST /api/robot/map/image` (robot bearer) `{ robot, mapName, width, height, resolution, originX, originY, png: base64, hash }` -> `204`.
- Dashboard: the Map page draws this image under the existing overlays (places, Otto's arrow + trail, LiDAR points, patient estimate, target), map meters -> pixels with origin + resolution (y axis flipped as in ROS-style maps; verify with the places' x,y landing on the corridors). Fit, zoom, pan, light/dark. Without an image: the current dots view, with a note.

## 2. Floors (maps)

- Robot reports its maps: from `getMultiFloorConfigAndPose` when it answers, else from any map list call javap shows; at least the current map from `getMapName`. Sent in the map frame as `maps: [{ name, floor? }]` and `mapName`.
- Places get `mapName` (the floor map they are on; empty = any). The dashboard groups places by floor; the robot only offers and walks to places of its current map. A journey to a place on another floor is refused with a clear message (no elevator control on this robot).
- Dashboard: a floor selector on the Map page and the robot card: "Switch Otto to <map>" with "Otto is standing at <spot on that map>" -> command `{ type: "switch_map", mapName, spot }`.
- Robot: refuse while guiding (answer via command done + log). Else `switchMap(mapName)`, then re-localise: `setPoseEstimate` at that spot's pose from `getPlaceList` (after the switch), check with `isRobotEstimate`, report every step in the log and the heartbeat (`mapName`, localised yes/no). Upload the new map image.

## 3. Settings without a rebuild

- `GET /api/robot/config` (robot bearer) -> `{ version, settings }`. The robot fetches it on start, every 60 s and on the command `{ type: "config_changed" }`, applies it live, logs "Settings vN applied", and shows the version in the staff menu. Unknown or out-of-range values fall back to the built-in default (log a warning). BuildConfig values stay the defaults.
- Settings (each with default, range, one-line help text on the dashboard):
  - walk: speed per pace (m/s, within what navigation accepts), walk side (left/right), first check after (s), check interval min/max (s), "nobody beside" seconds, ask a question every Nth stop, listening seconds, pickup timeout + reminder interval, lost-patient wait times.
  - way: blocked seconds before the detour, back-off on/off, automatic waypoints on/off, max waypoints, help after (s).
  - voice: volume (0-100, Android AudioManager music stream), ticket call languages, an extra note for Otto's speaking style, hide the system voice bar on/off, listening without wake word during walks on/off.
  - sensors: radar follower watch on/off, live map upload interval, send debug log lines on/off.
  - screen: the privacy line, show talk hints on/off.
- Dashboard: Settings -> Robot: grouped form, explanations, "Reset to defaults" per group, "Saved, Otto applied it at 18:42" status (from the robot's log/heartbeat `configVersion`). Changing a setting sends `config_changed`.

## 4. Debug

- Settings -> Debug: Otto's version, server, map/floor, localised, config version, the latest perf numbers (from the robot's "Perf (30 s)" log line: fps, slow frames, CPU), errors in the last hour, a link to Logs, "Recheck servers" (command `recheck`), and the raw config JSON.
