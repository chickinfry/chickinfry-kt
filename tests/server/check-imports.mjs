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

const { importObject } = await import(pathToFileURL(join(directory, '../kotlin', files[0].replace(/\.wasm$/, '.import-object.mjs'))));
const server = new WebAssembly.Instance(new WebAssembly.Module(bytes,
    { builtins: ['js-string'], importedStringConstants: "'" }), importObject).exports;
assert.equal(server.padWithZeros('12', 4), '0012');
assert.equal(server.padWithZeros('12345', 4), '12345');
assert.equal(server.asciiLowercase('Az-09'), 'az-09');
assert.throws(() => server.asciiLowercase('\u00c4'));
