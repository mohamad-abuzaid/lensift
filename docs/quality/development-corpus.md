# Development corpus and analysis-quality gate

This is a deterministic **development corpus, not field validation**. It is designed to prevent accidental regression in the shared analyzer. It is not evidence that Lensift will perform at the stated levels on a user's photo library, device camera pipeline, or image format mix.

## Provenance and split

`SyntheticCorpus` generates version `synthetic-corpus-v1` from seed `2026090201`. It uses no private/user photographs, decoders, platform APIs, or network access. There are 48 source families: 40 development families and 8 embargoed test families. A source family and every one of its variants are assigned together; there is no variant-level split.

Each family is a 64 by 64 deterministic luma pattern made from checker, diagonal, and ripple terms. Its generated variants cover byte-identical copies, 4-bit quantized recompression, nearest-neighbor resize to 63 by 64, brightness plus 18, a one-pixel crop, a burst-like one-pixel translation at 10 to 175 seconds, and a sharp high-frequency motion-streak perturbation. Negative blur examples include intentional bokeh circles and low-texture uniform walls. Positive blur examples use a deterministic radius-3 box blur.

The development split contains 68 assets, 4 exact-pair labels, 24 near-pair labels, and 4 blur labels. The 8-family test split contains 15 generated variants, but it exposes only family IDs and that variant count through `EmbargoedTestPartition`; it deliberately does not expose samples or labels to `QualityEvaluator` or `PolicySelector`. That type boundary, plus `AnalysisQualityTest`, prevents Task 8 selection or measurement from touching the test split. Plan 05 is the first planned point at which its labels may be opened for final evaluation.

## Evaluation and policy search

Labels are generated from source-family provenance. Predictions are generated separately: exact pairs come from equal content signatures; near pairs come from `PerceptualHash` plus `CandidateBucketer`; blur predictions come from `BlurAnalyzer`. For each task the gate records independent TP, FP, and FN, then precision and recall.

The selector evaluates the development split only. Its grid is pHash distance 0 through 20, capture window 15/30/60/90/120/180 seconds, and normalized aspect delta 0.005/0.01/0.02/0.04. Blur ceilings are paired, observed `(laplacian variance, edge density)` cut points from the generated development frames. Candidates must have exact precision and recall of 1.00, near precision of at least 0.90 and recall of at least 0.85, and blur precision of at least 0.85.

Among passing candidates, breadth is ranked by the sum of its grid ranks, with the numeric tuple as a deterministic tie breaker. Balanced is the broadest passing tuple. Conservative is the next no-wider passing tuple. Broad is the next no-tighter tuple that preserves every precision gate; when none exists it equals Balanced.

## Locked result

Analyzer version `shared-analysis-v1` selected these policies:

| Sensitivity | pHash | capture window | aspect delta | laplacian ceiling | edge ceiling |
| --- | ---: | ---: | ---: | ---: | ---: |
| Conservative | 20 | 180 s | 0.04 | 0.0005615785965522273 | 0.0 |
| Balanced | 20 | 180 s | 0.04 | 0.0005753471953795763 | 0.0 |
| Broad | 20 | 180 s | 0.04 | 0.0005753471953795763 | 0.0 |

Balanced metrics are exact TP/FP/FN `4/0/0` (precision/recall `1.00/1.00`), near `23/0/1` (`1.00/0.9583`), and blur `4/0/0` (`1.00/1.00`). Broad equals Balanced because no strictly wider grid tuple preserves the precision gates. `ReleasePolicyTest` locks the numeric values, versions, and monotonic ordering.

## Known blind spots

The corpus does not model JPEG/HEIC decoder differences, real optical bokeh, rolling-shutter motion, faces, text, screenshots, HDR tone mapping, color-only edits, cloud re-encodes, EXIF errors, large panoramas, or adversarially similar scenes. The threshold gate should therefore guide regression decisions, not substitute for field validation or user review.
