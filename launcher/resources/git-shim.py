#!/usr/bin/python
from __future__ import print_function
import socket, os, os.path, sys, json, subprocess

PORT=20280
BUF_SIZE=4096

# we hard-code the location of the fallback git since we control the devbox AMI
REAL_GIT="/usr/bin/git"

def receive_all(sock):
    """Receive all data coming through the socket and return it as bytes."""

    chunk = s.recv(BUF_SIZE)
    reply = []

    while len(chunk) != 0:
        reply += chunk
        chunk = s.recv(BUF_SIZE)

    return bytearray().join(reply)

def find_managed_dir(path):
    """Return the root managed directory if 'path' is under a managed directory, the empty string otherwise."""

    # find out what directories are synced
    dirfile = os.path.expanduser("~/.devbox/managed_dirs")
    if os.path.isfile(dirfile):
        with open(dirfile) as f:
            for line in f.readlines():
                dirname = line.strip()
                if os.path.commonprefix([path, dirname]) == dirname:
                    return dirname
    return ""

def handle_intrinsic(root):
    """Intercept certain git commands that can't be run remotely and resolve them locally.
    
    This function never returns (it calls sys.exit with the corresponding exit code)
    """

    if len(sys.argv) > 1 and sys.argv[1:] == ["rev-parse", "--show-toplevel"]:
        print(root)
        sys.exit(0)

root_managed = find_managed_dir(os.getcwd())

if root_managed == "":
    os.execv(REAL_GIT, [REAL_GIT] + sys.argv[1:])

# We are in a managed directory, so first try to intercept any command we can run locally
handle_intrinsic(root_managed)

# ..otherwise connect to the proxy command server and run it remotely
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect(('127.0.0.1', PORT))

relative_dir = os.path.relpath(os.getcwd(), os.path.expanduser("~"))
cmd = json.dumps({ "workingDir": relative_dir, "cmd": ["git"] + sys.argv[1:] })
s.send(cmd.encode('utf-8') + '\n')

reply = receive_all(s).decode("utf-8")
s.close()

response = json.loads(reply)

print(response.get("output", "No output received from Git Proxy, something went wrong."), end="")
sys.exit(response.get("exitCode", 0))
