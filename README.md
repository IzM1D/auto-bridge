# Auto-bridge

Client-side Fabric mod for Minecraft 26.2. Assign the "Align camera and center player" key in the Controls settings. It snaps the camera yaw to the closest cardinal or intercardinal direction, then moves the player with ordinary movement input to the nearest block center. Cardinal directions center the sideways coordinate; intercardinal directions center both X and Z.

- south: 0 degrees
- southwest: 45 degrees
- west: 90 degrees
- northwest: 135 degrees
- north: 180 degrees
- northeast: 225 degrees
- east: 270 degrees
- southeast: 315 degrees

The key is intentionally unbound by default, so it does not conflict with existing controls.

## Noob bridge mode

Assign the "Toggle Noob bridge mode" key in the Controls settings. Activate it while facing a cardinal direction, looking straight ahead, centered on the sideways block coordinate, and carrying a block in the hotbar. Press and hold Forward: the mod walks to the edge, turns around, crouches, looks down, places the next block, and moves backward to the next edge. Press the mode key again to exit.
