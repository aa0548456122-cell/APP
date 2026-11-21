# TrOCR Hebrew handwriting experiment

This branch is isolated from `main`. Mishkefet remains the production fallback.

## What is staged
- CI downloads the TrOCR-large INT8 CTC line recognizer, Hebrew charset, and compressed Hebrew character 6-gram LM into app assets only on this branch.
- Model card declares inputs `pixel_values` (B,3,384,384), `cols` (B,) int64 and output `log_probs` (B,288,198).
- Runtime inference is intended to remain entirely on-device through the existing ONNX Runtime Android dependency.

## Not yet enabled in the UI
The model is not yet wired into the handwritten-list scan flow. Before enabling it, implement and validate:
1. The exact line-crop preprocessing and `cols` semantics expected by the exported graph.
2. CTC + Hebrew 6-gram decoding compatible with the published binary LM format.
3. A side-by-side compare screen and fallback to Mishkefet on inference errors.
4. A build and real-device test against the user's handwritten sample.

Do not promote this branch to `main` until those checks pass. This is deliberate: a model bundled in the APK is not proof that its predictions are correctly decoded.
