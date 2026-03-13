# decrypt plugin

`decrypt` is a VitaLite-compatible external plugin that:

1. Accepts a sideloaded `.jar` or packet capture file (`.pcap` / `.pcapng`).
2. Extracts candidate JAR/class payloads from the capture.
3. Reconstructs Java source **stubs** (`.java`) per class into an output directory.

## Important behavior

- For direct `.jar` input, each class is parsed and emitted as a Java source skeleton.
- For capture input, the plugin scans byte streams for ZIP and class signatures and attempts recovery.
- Generated `.java` files are structural stubs (package/class/method/field names) and are intended for analysis.

## Files

- `DecryptPlugin.java` – plugin entry.
- `DecryptConfig.java` – config values.
- `DecryptService.java` – orchestration and file output.
- `PacketCaptureAnalyzer.java` – payload extraction from captures.
- `ClassFileParser.java` – low-level classfile parser.
- `JavaStubEmitter.java` – Java source generation.

## Configuration

- **Analyze on startup**: run immediately when enabled.
- **Input file**: absolute path to `.jar`, `.pcap`, or `.pcapng`.
- **Output directory**: where reconstructed `.java` files are written.
- **Overwrite existing files**: replace existing outputs.
