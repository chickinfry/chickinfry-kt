"""Exercise the packaged JVM through an inherited anonymous descriptor."""
import json
import os
import pathlib
import select
import subprocess
import sys
import threading
import zipfile

java, archive, plain, auth, scratch = sys.argv[1:]
root = pathlib.Path(scratch)
with zipfile.ZipFile(archive) as bundle:
    bundle.extractall(root)
lib = root / 'conformance-driver' / 'lib'
command = [java, '--enable-native-access=ALL-UNNAMED', '-cp', os.pathsep.join(str(p) for p in sorted(lib.glob('*.jar'))),
           'com.chickinfry.conformance.MainKt', '--plain-sdk', plain, '--auth-sdk', auth]
handshake = {'id': 'c:handshake', 'command': 'handshake', 'args': {'protocolVersions': [1], 'runId': 'pipe-test',
             'requiredCapabilities': [], 'target': {'language': 'kotlin', 'role': 'sdk', 'environment': 'jvm'}}}

def line(value):
    return (json.dumps(value, separators=(',', ':')) + '\n').encode()

def start(payload, eof=True):
    read_fd, write_fd = os.pipe()
    assert read_fd == 3, 'Expected an unused descriptor 3.'
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, pass_fds=(read_fd,))
    os.close(read_fd)
    def deliver():
        try:
            offset = 0
            while offset < len(payload):
                offset += os.write(write_fd, payload[offset:offset + 4096])
        except BrokenPipeError:
            pass
        finally:
            if eof: os.close(write_fd)
    thread = threading.Thread(target=deliver)
    thread.start()
    process.stdin.write(line(handshake)); process.stdin.flush()
    return process, thread, write_fd

def finish(process, successful):
    process.stdin.close()
    assert process.wait(timeout=5) == (0 if successful else 1)
    stdout = process.stdout.read()
    stderr = process.stderr.read()
    assert not stdout
    assert stderr == (b'' if successful else b'Conformance driver failed.\n')
    process.stdout.close(); process.stderr.close()

def bootstrap(payload, successful):
    process, thread, _ = start(payload)
    thread.join(5); assert not thread.is_alive()
    if successful:
        response = json.loads(process.stdout.readline())
        assert response['error'] is None
        assert 'client.transport' not in response['result']['capabilities']
    finish(process, successful)

checks = 0
for payload, successful in [(b'', True), (line({'ref': 'one', 'value': 'synthetic'}), True),
    (b'{}', False), (b'\xc3\n', False), (b'{"ref":"one","ref":"two","value":"v"}\n', False),
    (b'{"ref":"one","value":"v","extra":{"k":1,"\\u006b":2}}\n', False),
    (line({'ref': 'one', 'value': 'v'}) * 2, False)]:
    bootstrap(payload, successful); checks += 1
prefix, suffix = b'{"ref":"boundary","value":"', b'"}\n'
exact = prefix + b'x' * (65536 - len(prefix) - len(suffix)) + suffix
bootstrap(exact, True); checks += 1
bootstrap(exact[:-1] + b' \n', False); checks += 1
parts = []
for number in range(16):
    prefix = ('{"ref":"total:%d","value":"' % number).encode()
    parts.append(prefix + b'x' * (65536 - len(prefix) - len(suffix)) + suffix)
bootstrap(b''.join(parts), True); checks += 1
bootstrap(b''.join(parts) + b'\n', False); checks += 1
process, thread, write_fd = start(b'', eof=False)
thread.join()
assert not select.select([process.stdout], [], [], 0.3)[0]
os.close(write_fd)
assert json.loads(process.stdout.readline())['error'] is None
finish(process, True); checks += 1

for fixture in ('plain', 'auth'):
    process, thread, _ = start(b'')
    thread.join()
    assert json.loads(process.stdout.readline())['error'] is None
    for identifier, name, args in [('c:create', 'client.create', {'clientRef': 'client', 'fixture': fixture, 'origin': 'http://127.0.0.1:1', 'requestTimeoutMs': 100}),
        ('c:call', 'client.call', {'clientRef': 'client', 'operation': 'probe.identify', 'input': {'id': 'id'}})]:
        args.update(caseId='pipe-test', timeoutMs=2000)
        process.stdin.write(line({'id': identifier, 'command': name, 'args': args})); process.stdin.flush()
        response = json.loads(process.stdout.readline())
        assert response['id'] == identifier
        if name == 'client.create':
            assert response['result']['constructorNetworkRequests'] == 0
        else:
            assert response['error']['kind'] == 'sdk'
            assert response['error']['code'] == 'transport_error'
            assert response['error']['httpStatus'] is None
            assert response['error']['outcomeUnknown'] is False
            assert response['error']['rawTransportDetails'] is False
    finish(process, True); checks += 1
print(json.dumps({'checks': checks, 'platform': sys.platform, 'result': 'passed'}))
