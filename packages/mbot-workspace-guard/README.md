# @mbot/workspace-guard

Self-contained MIT workspace path fence for Agent file tools.

- MIT module, now consumed by the AGPL Bot host; its own license is unchanged.
- API is centralized in `src/index.js` so signatures can later align with `docs/architecture.md`.
- Path checks alone are not a full sandbox (no isolated UID, no TOCTOU-proof open).

```js
import { createWorkspaceGuard } from '@mbot/workspace-guard';

const guard = await createWorkspaceGuard('/data/data/.../files/workspace');
const abs = await guard.resolve('notes/a.txt'); // throws WorkspacePathError if outside
```

Run tests: `npm test` in this directory.

The Agent-facing contract is `resolveWorkspacePath(root, userPath)`: input must
be relative, without NUL, `..` components, backslashes or drive prefixes. Unlike
the older `guard.resolve`, this strict API rejects even absolute paths inside the
workspace. The host binds it to Android's trusted `filesDir/workspace`, and uses
it before media reads and its text file operations. It also rejects dangling
symlinks. Hardlinks and concurrent directory replacement remain outside the
guard's guarantees; this is not isolated-UID enforcement.
