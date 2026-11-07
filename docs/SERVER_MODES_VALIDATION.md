# Server modes: testing build

This branch adds two modes:

- With Ghost Path and Fabric API installed on a Fabric server and client, operator-authorized recording and ghost reruns share the latest prediction, including flight inspector data and area geometry. Late joins receive the latest snapshot. Recordings still fire the real machine and restore only tracked changes; players are not copied or restored.
- With client-only installation, received TNT/projectile states produce labelled estimates against loaded client blocks. Work is bounded to 32 entities, a 160-tick horizon and a 2 ms tick-loop budget per refresh. Copies never join the world; effects are suppressed. G toggles display. Estimates cannot be pinned.

Use the testing jar in `releases/ghostpath-0.1.0.jar`. Requires Minecraft 26.2, Java 25, Fabric Loader >=0.19.5 and Fabric API 0.161.0+26.2. Remote saved-path visibility, exact live-entity readouts and visual-setting commands are not synchronized; the display keys are local controls.

## Validation

Environment: portable Temurin 25.0.5, Gradle 9.8.2, Xvfb and software OpenGL. The existing floating Loom dependency resolved to 1.19.4; dependency declarations were not upgraded. Proxy and CA settings stayed outside the repository, with TLS verification enabled.

Gradle task arguments (invoked using the external JDK/proxy wrapper):

- `genSources`: passed. Official 26.2 source was inspected for projectile collision filtering and client TNT ticking.
- `runGameTest -PgtFilter=ghostpath-gametest:*snapshot_network*`: 2/2 passed. Lossless coordinates/area, partial reassembly and invalid/out-of-order packets checked.
- `build`: two runs each passed 137/137 server GameTests; final run 2m31s.
- `runClientGameTest` under `xvfb-run -a -s "-screen 0 1280x720x24"`: final full run completed all 9 configured client tests in 3m36s. Dedicated-server socket transfer preserved actual coordinates; TNT and projectile copies preserved originals; disconnect cleared remote state. The final projectile estimate had 46 points and legitimately hit a different entity at t+45.

Client development runs: two full runs passed and two failed. The first failure was an unassigned ID on a manually constructed TNT fixture. The second was an overly broad test assertion forbidding any entity hit, including later legitimate hits. The final assertion specifically rejects the original projectile's UUID as a target. A focused diagnostic also passed. Failed and successful logs are retained in the execution workspace.

The packaged jar contains Java 25 class files (major 69), the new transport/estimate classes, and no GameTest classes.

## Limits and outstanding validation

- An actual connection to an unmodified vanilla server was not tested end to end. Estimate simulation was tested on a remote client connected to the modded dedicated test server.
- Estimates do not simulate machine chains, explosions or their effects, unknown server plugins, or unloaded client chunks. Latency and server updates can change results. `estimate: fuse expires` predicts flight and fuse, not an explosion simulation.
- Transfers are capped at 8 MiB compressed, with 64 MiB decoded NBT accounting. Oversized predictions cannot be delivered and produce a server-log warning.
- The earlier stress investigation remains unresolved: loot and stuck-arrow tests each passed 3/3 stressed runs; the earlier full-suite stress batch passed 2/3 and failed once in MachineCommandTest's second-recording comparison. This feature work does not establish that flake is fixed.
- CycleDetector production reporting and SegmentDedup renderer integration remain unfinished from the earlier investigation.
- Optional authentication-service requests and audio-device setup failed in the virtual client environment; those warnings did not prevent the final test pass.
