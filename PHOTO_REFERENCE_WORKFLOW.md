# Photo-reference reconstruction

An optional agent workflow using existing Ashlar tools, not an image-to-3D engine or an additional verification stage. Ashlar does not fetch/analyze photographs itself; the calling agent needs image access and, when relevant, its own web tools. If those are unavailable, ask for usable images rather than claiming to have seen them.

## 1. Establish the reference

Use the user's supplied images first. For a named landmark without images, obtain a small useful reference set if image/web access is available. Prefer one overall view and one complementary view showing otherwise hidden geometry; do not search endlessly. Identify the building/version being reproduced and cite source links when external references inform the design.

Separate evidence into:

- **Observed:** visible silhouette, relative heights, roof forms, facade rhythm, material colors, openings, terraces and relationship to terrain.
- **Inferred:** depth, symmetry, hidden wings or approximate dimensions supported indirectly by other evidence.
- **Unknown:** concealed rear geometry, interiors, small ornament or dimensions without a reliable scale.

Do not silently turn an inference into a measured fact. Conflicting photographs may show different periods, renovations, viewpoints or separate buildings. State which version/view controls the design. Perspective and foreshortening make raw pixel lengths unreliable as real-world lengths.

## 2. Make a short reconstruction brief

Before placement, summarize the target in a compact brief, not a lengthy mandatory planning document:

- Reference views and front direction.
- One scale anchor: a supplied dimension, credible documented dimension, or explicitly chosen Minecraft footprint. State which it is.
- Approximate width:depth:height ratios and the main masses, relative to that anchor.
- Three to five distinctive features that must survive block simplification.
- Palette and important terrain relationships, including surrounding scenery if requested.
- Interior scope and uncertain details; distinguish documented rooms from plausible invented layouts.

Ask a question only when missing information materially changes scope, location, scale or destructive edits. Otherwise use conservative assumptions and state them. A single photograph does not justify claiming an exact hidden interior.

Example, fictional and not measured from a photograph:

> Chosen footprint 48 x 32 blocks, front south. Central hall about half the width; two lower wings; central roof roughly twice wing height. Preserve the stepped skyline, recessed entrance and repeating tall windows. Rear depth is inferred; interior circulation is an invented usable layout. Dark roof/light walls approximate the visible palette.

## 3. Translate evidence into buildable geometry

Prioritize silhouette, proportions, mass placement, facade rhythm and terrain integration before small ornament. Round consistently to blocks and disclose material or shape approximations. Do not enlarge every detail independently and accidentally change the overall proportions.

Use local coordinates and `transform` for orientation. Reuse blueprint components for repeated bays, towers, windows or rooms; palettes make material substitutions explicit. Optional architectural generators help where their supported geometry actually matches the reference. Do not force a landmark into a generic roof/tower generator that changes its defining shape.

If persistence is useful, put a short reconstruction brief in an ordinary blueprint's `description`, with observations/inferences/unknowns and source URLs in its advisory `constraints`. These fields are notes, not enforced geometry rules or automatic photographic validation. No new schema or tool is needed.

## 4. Place using the normal lightweight workflow

Use a recent relevant survey or one new survey to establish the site. If terrain adaptation matters, optional `mc_blueprint fit` can provide bounded additive foundations/descending entrances; it does not recreate arbitrary landscapes or refit relocated geometry.

Build the main masses and requested interior/exterior in a few purposeful passes. Do not render or inspect after every pass. Maintain player safety, existing write limits and rollback protection for terrain/structure edits. Snapshots do not preserve arbitrary NBT. Site approval is not permission for unrequested excavation or displacement of existing structures.

## 5. One visual check and an honest handoff

Use one appearance render with a viewpoint broadly comparable to the dominant reference. Angled renders are schematic map-colored shapes, not textured Minecraft screenshots or proof of photographic fidelity. Compare the silhouette, proportions and distinctive features; also use build feedback for concrete placement errors.

If a specific visible defect appears, make a targeted correction and, if necessary, a focused recheck. Do not begin a repeated whole-build audit. Explain unresolved approximations and unknown/invented areas at handoff.

A request for visual similarity, even "as close as possible," does not automatically request exact cell-by-cell auditing. `mc_verify` compares placed blocks with a frozen build specification; it does not compare a build with a photograph or establish that the specification is architecturally accurate. Use it only for explicitly requested exact block-state verification. No numeric photographic similarity score should be invented.

## Completion

Report what was reproduced, the chosen scale, important approximations and any unresolved uncertainty. Stop when the requested result is satisfactory and no concrete defect remains. Do not claim photographic accuracy beyond the available evidence.
