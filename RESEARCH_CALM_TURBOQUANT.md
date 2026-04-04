# RESEARCH_CALM_TURBOQUANT.md — Deep Research Session
> Scheduled: Next week
> Goal: Understand both papers, map to JarvisOS/Cactus stack, produce blog post.
> Last updated: March 2026

---

## Papers

| Paper | Authors | Venue | Link |
|---|---|---|---|
| Continuous Autoregressive Language Models (CALM) | Shao et al. | arXiv Oct 2025 | https://arxiv.org/abs/2510.27688 |
| TurboQuant | Zandieh, Mirrokni et al. | ICLR 2026 | https://research.google/blog/turboquant-redefining-ai-efficiency-with-extreme-compression/ |

---

## What We Already Know

### CALM
- Shifts LLM generation from discrete next-token prediction to continuous next-vector prediction
- Autoencoder compresses K tokens → single continuous vector; model predicts vectors, decoder reconstructs tokens
- Reduces autoregressive steps by factor of K — sweet spot is K=4
- Key inventions: Energy Loss (likelihood-free training), BrierLM (likelihood-free evaluation), rejection sampling for temperature control
- K=1 is worse than standard Transformer — payoff only comes at K≥2, crossover at K=4
- Chunk prediction errors poison K tokens at once — topic drift harder to correct than single token errors
- Autoencoder is 75M params — needs profiling on ARM before assuming it's feasible

### TurboQuant
- Targets the KV cache — memory storing attention keys/values so model doesn't recompute each step
- Two-stage: PolarQuant (polar coordinate rotation, eliminates per-block normalisation overhead) + QJL (1-bit residual correction, ensures unbiased inner products)
- Results: 6x memory reduction, 8x attention speedup on H100, 3-bit with zero accuracy loss
- Data-oblivious — no training or fine-tuning required
- Community already has working llama.cpp and MLX implementations
- Cactus Kernels already lists KV cache quantisation as a built-in feature — need to check what it currently uses

### Why They Complement Each Other
- CALM reduces **how many** KV cache entries are created (fewer autoregressive steps)
- TurboQuant compresses **each** KV cache entry
- Together: attack memory/compute bottleneck from both ends
- On-device ARM (JarvisOS) benefits more than cloud — every byte and every step saved matters more at the edge

---

## Research Agenda

### Session 1 — CALM Deep Dive
- [ ] Re-read paper in full — focus sections 2 (Autoencoder), 3 (Energy Transformer), 7 (Experiments)
- [ ] Understand posterior collapse problem and KL clipping fix
- [ ] Understand why discrete input outperforms continuous input fed back into the Transformer
- [ ] Map CALM inference loop onto Cactus generation pipeline
- [ ] Where does the autoencoder live in Cactus? Before or after tokenisation?
- [ ] What changes in `CactusWrapper.java` to support chunk-level generation?

### Session 2 — TurboQuant Deep Dive
- [ ] Read full paper (ICLR 2026)
- [ ] Understand PolarQuant — polar coordinate rotation trick in detail
- [ ] Understand QJL — how 1 bit corrects inner product bias
- [ ] Find community llama.cpp implementation, read the code
- [ ] What format does Cactus Kernels currently use for KV cache quant?
- [ ] Is TurboQuant a drop-in replacement or does it need a new kernel?

### Session 3 — Integration Mapping
- [ ] Draw full inference pipeline diagram with both techniques applied
- [ ] Identify exact Cactus touch points:
  - **Cactus Kernels** — KV cache storage format, attention computation
  - **Cactus Graph** — computation graph changes for chunk-level generation
  - **Cactus Engine** — API changes to expose CALM-style generation
- [ ] What changes in `CactusWrapper.java`?
- [ ] Does CALM's discrete-input-feedback pattern already exist in Cactus?
- [ ] Does TurboQuant interact with ObjectBox vector storage at all?

### Session 4 — Blog Draft
- [ ] Outline narrative
- [ ] Write technical sections
- [ ] Add diagrams: standard AR vs CALM, KV cache with/without TurboQuant
- [ ] Review and publish

---

## Key Questions to Answer

1. At K=4, CALM uses 4x fewer autoregressive steps. Does this mean 4x fewer KV cache entries too, or does the cache still grow at token level?
2. TurboQuant is data-oblivious — does it work on CALM's continuous vectors, or only on discrete token KV caches?
3. Cactus already has KV cache quantisation — what bit-width? Is TurboQuant an upgrade or replacement?
4. CALM autoencoder is 75M params. Feasible on JarvisOS target devices?
5. Energy head draws N=8 samples per step — does that hurt on ARM with limited parallelism?
6. Could BrierLM replace perplexity for JarvisOS model selection/evaluation?

---

## JarvisOS Integration Map

| Technique | Where It Fits | Potential Impact |
|---|---|---|
| CALM chunk generation | `CactusWrapper` → Cactus Engine generation loop | Fewer forward passes = faster on-device response |
| CALM autoencoder | New component before/after Cactus inference | 75M param overhead — needs ARM profiling |
| TurboQuant KV cache | Cactus Kernels KV cache layer | 6x memory saving = longer context on same RAM |
| BrierLM metric | Model evaluation/selection tooling | Better benchmark for on-device model quality |
| CALM + TurboQuant combined | Full inference pipeline | Compounding gains — fewer steps AND smaller cache |

---

## Resources

- CALM paper: https://arxiv.org/abs/2510.27688
- CALM code: https://github.com/shaochenze/calm
- TurboQuant blog: https://research.google/blog/turboquant-redefining-ai-efficiency-with-extreme-compression/
- TurboQuant PyTorch impl: https://github.com/tonbistudio/turboquant-pytorch
- Cactus repo: https://github.com/cactus-compute/cactus
- llama.cpp TurboQuant discussion: https://github.com/ggerganov/llama.cpp/discussions/20969
- AppFunctions (Google's Tool Registry): https://developer.android.com/ai/appfunctions

---

## Blog Outline

**Title:** *Predicting Lines, Not Points: How CALM and TurboQuant Could Transform On-Device AI*

1. The problem — token-by-token generation is inefficient, KV cache eats RAM
2. CALM — what it means to predict a "line" instead of a "point"
3. TurboQuant — compressing the working memory of attention
4. Why they're complementary — fewer entries AND smaller entries
5. What this means for JarvisOS — on-device, privacy-first at the edge
6. What needs to change in Cactus to make this real
