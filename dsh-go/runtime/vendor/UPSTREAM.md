# MiniMax Bot upstream snapshot

`minimax-bot/` was copied from [MiniMax-AI/MiniMax-Code-MiniApps](https://github.com/MiniMax-AI/MiniMax-Code-MiniApps/tree/0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8/plugins/minimax-ai/minimax-bot) at commit `0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8`, plugin version 0.7.18. Its MIT license and copyright notice remain in `minimax-bot/LICENSE`; test dependencies are defined in its own lockfile.

This source snapshot is not yet wired into the Android runtime. The upstream Bot is distributed as a Mini App, but this product does not implement a general-purpose Mini App container. Its Bot logic must be integrated as a built-in service with the MiniMax Code runtime, and validated on-device. The separate plugin marketplace is for the MiniMax Code plugin ecosystem.

Local modification: `miniapp/node/server.mjs` accepts an optional host-only request authorizer before all routes. The built-in Android service wrapper uses it to authenticate the actual listener; upstream business logic and licensing are retained. An unauthenticated call to upstream `start` is only appropriate for isolated test fixtures, not the production Android launcher.
