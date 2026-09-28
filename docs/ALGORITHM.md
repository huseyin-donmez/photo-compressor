# Resize Algorithm Contract

Single source of truth for both the Swift and Kotlin implementations. Both platforms must use these exact numbers and rules; shared fixtures in `testdata/` assert identical behavior.

## Priority

1. Preserve resolution
2. Optimize quality
3. Reduce resolution if necessary
4. Verify output size (hard guarantee: output ≤ target)

## Parameters (`ResizeParameters`, identical on both platforms)

| Parameter | Value |
|---|---|
| Safety margin | `targetEnc = target × 0.97` (measured bytes must be ≤ `targetEnc`) |
| Initial quality `q0` | 0.80 |
| Quality ceiling `qHi` | 0.95 |
| Quality floor `qLo` (preferred) | 0.30 |
| Absolute quality floor `qAbs` | 0.10 (only at resolution floor, then forced fit) |
| Quality tolerance `Q_TOL` | 0.03 (stop when `hi − lo ≤ Q_TOL`) |
| Max bisect iterations / level | 5 |
| First reduction scale | `clamp(sqrt(targetEnc / sizeAtFloor) × 0.9, 0.40, 0.85)` |
| Successive reduction factor | ×0.70 |
| Max resolution reductions | 3 (⇒ ≤ 4 levels + quality widening at floor) |
| Resolution floor | do not voluntarily go below: longest edge ≥ 1024 px **and** min edge ≥ 512 px |
| Absolute min edge | 64 px (hard; forced fit may go to 64) |
| Forced-fit fallback | at floor: `q = qAbs`, scale ×0.75 per iteration until ≤ target, ≤ 8 iterations |
| Encode budget | ≤ 24 encodes per image for normal paths (bisect ≤ 5/level) |
| Memory-capped full decode | pixels ≤ `maxDecodePixels` (iOS default 60 MP, Android = `0.30 × maxMemory / 4` bytes as pixels, cap 60 MP) |
| Min allowed target (product) | 100 KB |

## Pseudo-code

```
resize(session, target):
  header = readHeader()                      # header-only, no decode
  format = chooseOutputFormat(header)        # nil → UnsupportedFormat
  targetEnc = target × 0.97

  if header.bytes <= target:
    data = passThroughStripGPS()             # byte surgery only, NEVER re-encodes
    if data != nil and data.size <= target: return PASS_THROUGH(data)

  scale   = memoryScale(header)              # ≤ 1, decode-memory cap
  qFloor  = qLo
  estimateUsed = false
  level = 0
  loop:                                      # ≤ 8 iterations, safety
    level += 1
    image = decode(longestEdge × scale)      # orientation baked, sRGB policy below
    (best, bytesAtFloor) = bisect(image, [qFloor, qHi], seed):
        q = seed
        for ≤ 5:
          data = encodeFinal(image, q)       # encode + merged (GPS-stripped) metadata
          if data ≤ targetEnc: best = (q, data); lo = q
          else: hi = q; remember smallest failing data
          if hi − lo ≤ Q_TOL: break
          q = (lo + hi) / 2
    if best: return PROCESSED(best)

    floorScale = resolutionFloorScale(header)          # aspect-preserving, ≥ what satisfies both floor constraints
    if scale ≤ floorScale + ε:
      if qFloor == qLo: qFloor = qAbs; continue        # widen quality at floor, same scale
      else: break                                       # even qAbs failed → forced fit
    next = estimateUsed ? scale × 0.70
                         : clamp(sqrt(targetEnc/bytesAtFloor) × 0.9, .40, .85)
    estimateUsed = true
    next = max(next, floorScale)                        # never dip below floor via estimate
    if next ≥ scale × 0.95: next = scale × 0.70
    scale = next; continue

  forcedFit:                                             # runs only in pathological cases
    for ≤ 8: scale ×= 0.75, decode, encodeFinal(q=qAbs); if fits: return PROCESSED
    if dims.minEdge > 64: continue reducing
    throw TargetInfeasible                               # target below ~1 KB

  write data to output → stat size; if stat > target: throw TargetExceeded (belt & braces)
```

Pass-through never consumes a usage credit. `PROCESSED` consumes exactly one credit, persisted immediately before temp cleanup.

## Output format matrix (automatic, no user option)

| Input | iOS output | Android output |
|---|---|---|
| JPEG | JPEG | JPEG |
| PNG with alpha | PNG (resolution reduction only — no quality axis) | same |
| PNG without alpha | JPEG (preserve resolution is priority #1) | JPEG |
| HEIC/HEIF | HEIC | **JPEG** (no native HEIC encoder; androidx.heifwriter rejected) |
| WebP | JPEG (no iOS WebP encoder; libwebp rejected) | WebP (native) |
| GIF / BMP | first frame → JPEG, or PNG if alpha | same |
| RAW / TIFF / video / PDF / unknown | **Unsupported → skip item, no credit, batch continues** | same |

Alpha detection (feeds the matrix) reads container bytes, never decoder defaults: PNG → IHDR color type, WebP → VP8X/VP8L flags, GIF → the transparent flag of the first frame's Graphic Control Extension (decoders report alpha for every GIF), otherwise decoder properties.

## Metadata policy

- **GPS/location: always stripped**, including pass-through.
- **Pass-through is byte-level — never a re-wrap.** Re-wrapping via the image
  destination API re-encodes pixels (measured) and keeps GPS, so it is forbidden.
  The stripper's contract:
  - JPEG: marker-segment walk. XMP `APP1` segments are dropped (self-delimiting,
    safe splice); the EXIF GPS pointer entry (`0x8825`) is retagged to the unknown
    tag `0xFFFF` and the GPS IFD plus its out-of-line value bytes are zeroed
    in place — strictly length-neutral, no offset fixups, MakerNote/thumbnail/
    SubIFDs untouched.
  - Non-JPEG containers: bytes are copied only when provably clean (no GPS per
    decoder properties, no XMP packet markers); otherwise pass-through is refused
    and the pipeline re-encodes, which strips GPS for sure. A GPS-bearing HEIC
    therefore always re-encodes in v1 — privacy wins over free pass-through.
  - Fail-safe: stripped bytes are re-parsed with the decoder; if it no longer
    parses or GPS reads back as non-nil, fall back to the re-encode path.
- **Orientation: always baked into pixels** at decode → re-encode outputs are
  upright with no Orientation tag. Pass-through copies bytes untouched, so a
  stored Orientation stays — pixels were never transformed.
- ICC: iOS preserves/embeds the source profile (ImageIO, no extra full-res buffer); Android forces `inPreferredColorSpace = sRGB` at decode. Outputs are always color-correct.
- DateTime / Make / Model / Lens / exposure / Copyright: preserved when the container supports EXIF.
- Software tag: `ImageResizer <version>`, re-encode paths only — pass-through
  adds nothing to copied bytes.

## Error taxonomy (credit only on successful, saved output)

| Error | Retry | Skip | Continue batch | Credit |
|---|---|---|---|---|
| Unsupported format | no | yes | yes | never |
| Corrupted image | no | yes | yes | never |
| Permission denied | once, after re-prompt | — | no (Share Sheet fallback) | never |
| Storage full | no | — | abort batch | never |
| Out of memory | yes ×1 at 2× downsample | yes on 2nd failure | yes (lower global decode budget) | never |
| Cancelled | — | — | stops | never |
| Unknown processing error | no | yes | yes | never |
| Output save failed | yes ×1 | — | yes (hold file for Share Sheet) | only on success |
| Target infeasible (target < ~1 KB) | no | yes | yes | never |
