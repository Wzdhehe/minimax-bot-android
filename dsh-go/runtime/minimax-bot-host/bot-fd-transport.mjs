// SPDX-License-Identifier: AGPL-3.0-only
// Trusted launcher adapter for already-connected sockets. It neither creates
// a listening socket nor grants a pathname, endpoint or model credential.
import { closeSync, fstatSync } from 'node:fs';
import { Server } from 'node:http';
import { Socket } from 'node:net';

const MAX_CONNECTIONS = 64;
const failure = (code) => Object.assign(new Error(code), { code });

/** One transport belongs to one built-in Bot server and one runtime lifetime. */
export function createBotFdTransport() {
  let server;
  let closed = false;
  const sockets = new Set();
  const ownedFds = new Set();

  return Object.freeze({
    // Only the trusted service wrapper passes its own HTTP server here.
    async listenServer(candidate) {
      if (closed || server || !(candidate instanceof Server) || candidate.listening) {
        throw failure('bot_transport_unavailable');
      }
      server = candidate;
    },
    /** Takes ownership of an already-connected, owner-granted socket FD. */
    accept(fd) {
      if (!Number.isSafeInteger(fd) || fd < 3) throw failure('bot_transport_invalid');
      // A duplicate number is already owned by a live Socket. Do not close it
      // on rejection, as that would silently kill the existing connection.
      if (ownedFds.has(fd)) throw failure('bot_transport_invalid');
      let socket;
      try {
        if (closed || !server) throw failure('bot_transport_unavailable');
        if (sockets.size >= MAX_CONNECTIONS) throw failure('bot_transport_busy');
        if (!fstatSync(fd).isSocket()) throw failure('bot_transport_invalid');
        socket = new Socket({ fd, readable: true, writable: true });
        sockets.add(socket);
        ownedFds.add(fd);
        socket.once('close', () => { sockets.delete(socket); ownedFds.delete(fd); });
        // Transport errors never surface platform text or crash the runtime.
        socket.on('error', () => {});
        server.emit('connection', socket);
        socket.resume();
      } catch (error) {
        if (socket) socket.destroy();
        else try { closeSync(fd); } catch {}
        if (typeof error?.code === 'string' && error.code.startsWith('bot_transport_')) throw error;
        throw failure('bot_transport_invalid');
      }
    },
    close() {
      if (closed) return;
      closed = true;
      for (const socket of sockets) socket.destroy();
      sockets.clear();
      server = undefined;
    },
  });
}
