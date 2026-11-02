# @mbot/workspace-guard

Self-contained MIT workspace path fence for Agent file tools.

- Not linked from DSH Go / AGPL trees.
- API is centralized in `src/index.js` so signatures can later align with `docs/architecture.md`.
- Path checks alone are not a full sandbox (no isolated UID, no TOCTOU-proof open).

```js
import { createWorkspaceGuard } from '@mbot/workspace-guard';

const guard = await createWorkspaceGuard('/data/data/.../files/workspace');
const abs = await guard.resolve('notes/a.txt'); // throws WorkspacePathError if outside
```

Run tests: `npm test` in this directory.
