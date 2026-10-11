// SPDX-License-Identifier: AGPL-3.0-or-later

import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import net from "node:net";
import { test } from "node:test";

import { PluginClient } from "./plugin-client.js";

/** Unmasks the first client text frame (payload under 126 bytes). */
function readFrame(buf: Buffer): string {
    const len = buf.readUInt8(1) & 0x7f;
    const mask = buf.subarray(2, 6);
    const data = Buffer.from(buf.subarray(6, 6 + len));
    for (let i = 0; i < data.length; i++) data.writeUInt8(data.readUInt8(i) ^ mask.readUInt8(i % 4), i);
    return data.toString("utf8");
}

function textFrame(text: string): Buffer {
    const payload = Buffer.from(text, "utf8");
    return Buffer.concat([Buffer.from([0x81, payload.length]), payload]);
}

test("an attempt that is never answered is abandoned and retried", async () => {
    // Connection 1 accepts TCP but never answers the upgrade; connection 2 upgrades but never
    // answers auth; connection 3 authenticates. Without the handshake timeout the client stays
    // stuck on connection 1 forever.
    let connections = 0;
    const sockets: net.Socket[] = [];
    const server = net.createServer(socket => {
        sockets.push(socket);
        const n = ++connections;
        let upgraded = false;
        socket.on("data", chunk => {
            if (n === 1) return;
            if (!upgraded) {
                const key = /Sec-WebSocket-Key: (.+)\r\n/i.exec(chunk.toString())![1];
                const accept = createHash("sha1").update(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
                socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
                upgraded = true;
                return;
            }
            if (n === 3 && JSON.parse(readFrame(chunk)).method === "auth") {
                socket.write(textFrame(JSON.stringify({ id: "auth-1", ok: true, result: {} })));
            }
        });
        socket.on("error", () => {});
    });
    await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
    const port = (server.address() as net.AddressInfo).port;
    const client = new PluginClient({ url: `ws://127.0.0.1:${port}`, token: "t", defaultTimeoutMs: 1000, handshakeTimeoutMs: 200 });
    try {
        client.start();
        const deadline = Date.now() + 8000;
        while (!client.isConnected() && Date.now() < deadline) await new Promise(r => setTimeout(r, 50));
        assert.equal(client.isConnected(), true, `connected after ${connections} attempts`);
        assert.equal(connections, 3);
    } finally {
        client.close();
        for (const s of sockets) s.destroy();
        await new Promise(resolve => server.close(resolve));
    }
});
