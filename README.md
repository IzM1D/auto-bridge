# Auto-bridge

Client-side Fabric mod for Minecraft 26.2.

## Noob bridge mode

Assign the "Toggle Noob bridge mode (align and center)" key in the Controls settings. Normally, the bridge continues in the direction you are facing; hold Forward (W) to build. When you are already at an unbridged edge, looking down at the supporting block at about 77 degrees, the bridge instead continues behind you (yaw + 180 degrees); hold Backward (S) to build. Looking in an intercardinal direction enables diagonal building: the player remains at the selected corner, places at pitch 72 degrees, and alternates the right and left faces relative to the player. Pressing the key aligns the view to the nearest direction; cardinal building is also centered. Carry a block in the hotbar. The mod places a block immediately when already at an edge, or walks to the edge first, then crouches, looks down, places the next block, and moves backward to the next edge. Press the same key again to exit.

The key is intentionally unbound by default, so it does not conflict with existing controls.

## Settings

Assign the "Open Auto-bridge settings" key in the Controls settings. Outside build mode it opens a pause-style settings screen (and pauses a single-player world). The screen controls smooth camera alignment and its duration, as well as optional centering/camera imprecision and the chance of applying it. Settings are saved in `config/auto-bridge.properties`.
