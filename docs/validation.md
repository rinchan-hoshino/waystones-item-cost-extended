# Runtime validation

The payment and native teleport probes passed on all 16 supported Minecraft versions. Each version was tested with the loader shown below.

| Minecraft | Loader | Assertions | Result |
| --- | --- | ---: | --- |
| 1.20.4 | fabric | 19 | PASS |
| 1.20.6 | fabric | 19 | PASS |
| 1.21 | neoforge | 19 | PASS |
| 1.21.1 | neoforge | 19 | PASS |
| 1.21.10 | neoforge | 19 | PASS |
| 1.21.11 | neoforge | 19 | PASS |
| 1.21.2 | fabric | 19 | PASS |
| 1.21.3 | neoforge | 19 | PASS |
| 1.21.4 | neoforge | 19 | PASS |
| 1.21.5 | neoforge | 19 | PASS |
| 1.21.6 | neoforge | 19 | PASS |
| 1.21.7 | neoforge | 19 | PASS |
| 1.21.8 | neoforge | 19 | PASS |
| 1.21.9 | neoforge | 19 | PASS |
| 26.1.2 | neoforge | 15 | PASS |
| 26.2 | neoforge | 15 | PASS |

The six screenshots were captured from an authenticated Minecraft 1.21.1 NeoForge client connected to a local server. They show combined emerald and oak-log costs, iron-nugget payments, and iron-block payments with returned ingots.

Run `python3 runtime-tests/run.py --output <directory> --eula <existing-accepted-eula.txt>` with the Java toolchain environment configured. Each case writes its own log and result JSON.
