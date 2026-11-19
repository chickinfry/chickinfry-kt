import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const directory = process.argv[2];
const files = readdirSync(directory).filter(name => name.endsWith('.wasm'));
assert.equal(files.length, 1, 'Expected one production server module');
const bytes = readFileSync(join(directory, files[0]));
const imports = WebAssembly.Module.imports(new WebAssembly.Module(bytes));
console.log(JSON.stringify(imports.filter(item => item.module !== "'")));
assert.deepEqual(imports.filter(item => item.module === 'wasm:js-string' &&
    ['fromCharCodeArray', 'intoCharCodeArray'].includes(item.name)), [],
    'Runtime server must not require GC array string functions');

const { importObject, setWasmExports } = await import(pathToFileURL(join(directory, '../kotlin', files[0].replace(/\.wasm$/, '.import-object.mjs'))));
const server = new WebAssembly.Instance(new WebAssembly.Module(bytes,
    { builtins: ['js-string'], importedStringConstants: "'" }), importObject).exports;
setWasmExports(server);
assert.equal(server.padWithZeros('12', 4), '0012');
assert.equal(server.padWithZeros('12345', 4), '12345');
assert.equal(server.asciiLowercase('Az-09'), 'az-09');
assert.throws(() => server.asciiLowercase('\u00c4'));

const anonymous = JSON.stringify({version: 1, session: null});
const session = id => JSON.stringify({version: 1, session: {
    id: `session-${id}`, user: {id: `user-${id}`, email: `u${id}@example.test`},
}});
const error = (status, code) => ({status, body: {error: code}});
const response = async (path, auth, rid = 10) => JSON.parse(await server.authRequest(rid, path, auth));
assert.deepEqual(await response('/probe.protected', anonymous), error(401, 'unauthenticated'));
for (const invalid of [
    null, 'null', '{"version":1}', '{"version":2,"session":null}', '{"version":1.0,"session":null}',
    '{"version":1,"session":null,"version":1}', session(1).replace('u1@example.test', ''),
    session(1).replace('"email":', '"expires_at":"ignored","email":'),
]) {
    assert.deepEqual(await response('/probe.protected', invalid), error(500, 'operation_failed'));
}
assert.deepEqual(await response('/auth.session', session(1)), error(404, 'not_found'));
assert.equal(server.authDispatches(), 0);
assert.deepEqual(await response('/probe.public', anonymous), {status: 200, body: {session: 'anonymous'}});
const responses = await Promise.all([response('/probe.protected', session(1), 11), response('/probe.public', session(2), 12)]);
for (let i = 1; i <= 2; i++) {
    assert.deepEqual(responses[i - 1], {status: 200, body: {session: `session-${i}`, user: `user-${i}`, email: `u${i}@example.test`}});
}

assert.deepEqual(JSON.parse(await server.authFreeRequest(null)),
    {status: 200, body: {authorization: 'Bearer customer', cookie: 'app=value'}});
assert.deepEqual(JSON.parse(await server.authFreeRequest(session(1))), error(500, 'operation_failed'));

const calls = [];
globalThis.__chik = { db: async bytes => {
    calls.push(JSON.parse(new TextDecoder().decode(bytes)));
    return new TextEncoder().encode('{"rows":[]}');
} };
const hook = (kind, user = 'normal') => JSON.stringify({version: 1, hook: kind,
    user: {id: user, email: 'user@example.test', createdAt: '2026-11-20T00:00:00.000000Z'},
});
const invokeHook = async (kind, lease, user) => JSON.parse(await server.authHook(11, hook(kind, user), lease));
assert.deepEqual(await invokeHook('userCreating', 0), error(500, 'operation_failed'));
assert.deepEqual(await invokeHook('userCreated', 9), error(500, 'operation_failed'));
assert.equal(calls.length, 0);
assert.equal(server.postCommitHooks(), 0);
assert.deepEqual(await invokeHook('userCreating', 9, 'fail'), error(500, 'user_creation_failed'));
assert.equal(calls.length, 1);
assert.equal(calls[0].rid, 11);
assert.equal(calls[0].lease, 9);
assert.equal(calls[0].op, 'exec');
assert.deepEqual(await response('/probe.retained', session(2), 12), error(500, 'operation_failed'));
assert.equal(calls.length, 1);
assert.deepEqual(await invokeHook('userCreating', 10), {status: 200, body: {}});
assert.equal(calls.length, 2);
assert.equal(calls[1].lease, 10);
assert.deepEqual(await invokeHook('userCreated', 0), {status: 200, body: {}});
assert.equal(server.postCommitHooks(), 1);
assert.equal(calls.length, 2);
console.log('Verified Kotlin authentication frame, credential header boundary, and borrowed hook lease');
