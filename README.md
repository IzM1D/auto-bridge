# Auto-bridge

Client-side Fabric mod for Minecraft 26.2.

## Build modes

Assign the "Toggle build mode (align and center)" key in the Controls settings. Pressing it aligns the view to the nearest heading the selected mode can build along, centers the player on the support block when that mode's stance needs it, and then starts the mode. Press the same key again to exit. The key is intentionally unbound by default, so it does not conflict with existing controls.

Which mode the key starts is picked in the settings screen. Every mode brings its own stance, its own set of headings and its own click pace, so the settings screen is the only place that needs touching to switch between them. So far there is one mode:

### Noob bridging

Normally the bridge continues in the direction you are facing; hold Forward (W) to build. When you are already at an unbridged edge, looking down at the supporting block at about 82 degrees, the bridge instead continues behind you (yaw + 180 degrees); hold Backward (S) to build. Looking in an intercardinal direction enables diagonal building: the player remains at the selected corner, places at pitch 76 degrees, and alternates the right and left faces relative to the player. Cardinal building is also centered. Carry a block in the hotbar. The mod places a block immediately when already at an edge, or walks to the edge first, then crouches, looks down, places the next block, and moves backward to the next edge.

## Settings

Assign the "Open Auto-bridge settings" key in the Controls settings. Outside build mode it opens a pause-style settings screen (and pauses a single-player world). The screen selects the bridging mode and controls smooth camera alignment and its duration, as well as optional centering/camera imprecision and the chance of applying it. Settings are saved in `config/auto-bridge.properties`, the mode under the `bridgeMode` key.

## Adding a kind of building

Everything a mode needs lives in `auto.bridge.client.build`:

- `BridgeMode` — the interface a kind of building implements: it starts, ticks, reports the movement input and whether it crouches, and stops. One instance serves a single run, so a mode can keep its stance in plain fields.
- `BridgeModeType` — the registry. One entry gives the mode its id, its chat and screen names, its factory and its `BuildProfile`. The settings switcher cycles this enum, so a new entry shows up in the GUI on its own.
- `BuildProfile` — the per-mode numbers: the yaw step it aligns to (which headings it works at), whether its diagonal stance needs centering, its clicks per second and how long a placement is given to confirm.
- `BuildModeManager` — the static facade the mixins and `AutoBridgeClient` talk to; a mode never has to be wired into them.
- `BridgeGeometry`, `MovementInput`, `PlacementPace` — shared yaw/support/travel maths, the movement keys a mode asks for, and the CPS limiter with human-like jitter.

So a new kind of building is one `BridgeMode` class plus one `BridgeModeType` entry, and a `bridge_mode.auto-bridge.<id>` lang key for its name.
