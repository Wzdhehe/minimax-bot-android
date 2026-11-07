# Numeric renderer compatibility fixtures

These test-only JSON files retain functional geometry, state mappings and frame
timings used by the existing AGPL renderer tests, under synthetic IDs. They contain
no artwork, source imagery, brand labels, URLs, or original licensing claims.
The `.bounds` files are numeric pixel extents, not images. Dart paints plain solid
rectangles within those extents; native tests use the same extents to test layout
math. These fixtures are **not** bundled in the application or available as pets.
No DSH/DeepSeek mascot image is copied here. The existing renderer source and its
upstream attribution remain unchanged.
