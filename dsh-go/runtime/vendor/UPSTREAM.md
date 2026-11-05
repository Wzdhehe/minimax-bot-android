# MiniMax Bot upstream snapshot

`minimax-bot/` was copied from [MiniMax-AI/MiniMax-Code-MiniApps](https://github.com/MiniMax-AI/MiniMax-Code-MiniApps/tree/0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8/plugins/minimax-ai/minimax-bot) at commit `0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8`, plugin version 0.7.18. Its MIT license and copyright notice remain in `minimax-bot/LICENSE`; test dependencies are defined in its own lockfile.

This source snapshot is not yet wired into the Android runtime. The upstream Bot is distributed as a Mini App, but this product does not implement a general-purpose Mini App container. Its Bot logic must be integrated as a built-in service with the MiniMax Code runtime, and validated on-device. The separate plugin marketplace is for the MiniMax Code plugin ecosystem.

Local modification: `miniapp/node/server.mjs` accepts an optional host-only request authorizer before all routes. The built-in Android service wrapper uses it to authenticate the actual listener; upstream business logic and licensing are retained. An unauthenticated call to upstream `start` is only appropriate for isolated test fixtures, not the production Android launcher.

T-Sandbox adds an optional host-only `resolveMediaPath` hook before media `stat`/read/stream operations. The built-in host always supplies its workspace guard and accepts only relative paths; a Bot mentioning an outside absolute filename no longer authorizes reading it. The original absolute-path behavior remains only for upstream-compatible direct `start` callers/test fixtures. This patch does not alter the upstream runtime pin or introduce a general Mini App host.

T-Providers adds `/api/providers/test`, protected by the same host authorizer. It accepts provider/model identifiers only and calls an optional host-owned broker; request keys are rejected. Without a broker it reports unavailable, and responses contain only probe status, never saved credentials or upstream response text. This is a built-in Bot host extension, not an upstream provider-management implementation.

T-Usage adds an authenticated `/api/usage-quota` read-only route and an optional host-owned quota capability. The wrapper projects only public plan/quota fields. No capability means no OAuth account integration and produces signed-out state; account errors are not converted to zero usage. API keys are not used for this account capability.

The host may optionally supply `listenServer` to serve already-connected, explicitly granted socket FDs without a guest listener. With no hook the upstream TCP listen behavior remains. Authentication, routes and Bot business logic remain unchanged. This is a transport seam, not a complete Android launcher.

The host may optionally supply `storePersistence` for the built-in Bot's state, chat JSON and usage ledger. Default callers retain the original filesystem persistence. Usage reads now treat only a missing file as empty; other read errors propagate so an unavailable ledger cannot silently cause duplicated accounting. This hook does not adapt Bot memory, uploads, or the mcode runtime filesystem.

The host may optionally supply `memoryFilesystem` for the Bot memory store's existing transaction and recovery operations. Default callers retain the original Node filesystem implementation; the bounded adapter and its shared-lease restrictions are host-owned. This seam does not adapt media or the mcode runtime filesystem.

The built-in host can explicitly opt into `filesUnavailable` when its isolated runtime has state and memory brokers but no media capability. In that mode the server refuses static client assets and media/upload/avatar routes, while `Engine` and `Team` also refuse attachment and custom-avatar operations before filesystem access. Default upstream callers retain their previous behavior. This does not implement file transfers or a general Mini App container.
