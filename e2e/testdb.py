"""
The two MongoDB commands the runner needs (ping, drop the test database), in plain stdlib so the
suites have no dependencies. Deliberately unable to reach anything but a loopback server, or to
drop anything but a database the runner named.
"""
import re
import socket
import struct
import uuid

HOST = "127.0.0.1"
NAME = re.compile(r"moses_e2e_[0-9a-f]{12}")


def new_name():
    return f"moses_e2e_{uuid.uuid4().hex[:12]}"


def _cstring(value):
    return value.encode() + b"\x00"


def _command(port, command, database):
    db = _cstring(database)
    fields = (b"\x10" + _cstring(command) + struct.pack("<i", 1)
              + b"\x02" + _cstring("$db") + struct.pack("<i", len(db)) + db)
    document = struct.pack("<i", len(fields) + 5) + fields + b"\x00"
    payload = struct.pack("<I", 0) + b"\x00" + document
    message = struct.pack("<iiii", 16 + len(payload), 1, 0, 2013) + payload  # 2013 = OP_MSG
    with socket.create_connection((HOST, port), timeout=30) as connection:
        connection.sendall(message)
        reply = b""
        while len(reply) < 4 or len(reply) < struct.unpack_from("<i", reply)[0]:
            chunk = connection.recv(65536)
            if not chunk:
                break
            reply += chunk
    return b"\x01ok\x00" + struct.pack("<d", 1.0) in reply


def ping(port):
    try:
        return _command(port, "ping", "admin")
    except OSError:
        return False


def drop(port, database):
    if not NAME.fullmatch(database):
        raise ValueError(f"refusing to drop {database!r}: not a database the e2e runner created")
    return _command(port, "dropDatabase", database)
