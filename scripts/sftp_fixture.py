"""Unprivileged disposable SFTP fixture. Test-only dependency: paramiko (LGPL-2.1).
No source from Paramiko is included; this adapter is independently written.
"""
import errno, os, pathlib, socket, threading
import paramiko

class Authentication(paramiko.ServerInterface):
    def __init__(self, public_key, metrics): self.public_key = public_key; self.metrics = metrics
    def record(self, method):
        with self.metrics.open("a") as out: out.write(method + "\n")
    def get_allowed_auths(self, username): return "password" if username in ("koreader-no-password","password-test") else "publickey"
    def check_auth_none(self, username):
        self.record("none")
        return paramiko.AUTH_FAILED
    def check_auth_password(self, username, password):
        self.record("password") # never record the password
        allowed = username == "koreader-no-password" or (username == "password-test" and password == "fixture-password")
        return paramiko.AUTH_SUCCESSFUL if allowed else paramiko.AUTH_FAILED
    def check_auth_publickey(self, username, key):
        self.record("publickey")
        return paramiko.AUTH_SUCCESSFUL if key.asbytes() == self.public_key.asbytes() else paramiko.AUTH_FAILED
    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

class Files(paramiko.SFTPServerInterface):
    def __init__(self, server, *args, **kwargs):
        self.root = pathlib.Path(kwargs.pop("root"))
        super().__init__(server, *args, **kwargs)
    def path(self, path):
        candidate = pathlib.Path(path)
        if not candidate.is_absolute(): candidate = self.root / candidate
        # lstat must be able to see a symlink, so don't resolve the leaf.
        if candidate != self.root and os.path.commonpath([str(candidate.parent.resolve()), str(self.root)]) != str(self.root): raise OSError(errno.EACCES, "Outside fixture")
        return candidate
    def stat(self, path):
        try: return paramiko.SFTPAttributes.from_stat(os.stat(self.path(path)))
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def lstat(self, path):
        try: return paramiko.SFTPAttributes.from_stat(os.lstat(self.path(path)))
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def canonicalize(self, path): return str(pathlib.Path(path).resolve())
    def list_folder(self, path):
        try:
            result = []
            for child in self.path(path).iterdir():
                attrs = paramiko.SFTPAttributes.from_stat(child.lstat()); attrs.filename = child.name; result.append(attrs)
            return result
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def open(self, path, flags, attr):
        try:
            fd = os.open(self.path(path), flags, 0o600)
            mode = "r+b" if flags & os.O_RDWR else "wb" if flags & os.O_WRONLY else "rb"
            handle = paramiko.SFTPHandle(flags); stream = os.fdopen(fd, mode)
            if flags & (os.O_WRONLY | os.O_RDWR): handle.writefile = stream
            if not flags & os.O_WRONLY: handle.readfile = stream
            return handle
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def mkdir(self, path, attr):
        try: self.path(path).mkdir(); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def remove(self, path):
        try: self.path(path).unlink(); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)
    def rename(self, oldpath, newpath):
        try:
            source, destination = self.path(oldpath), self.path(newpath)
            if destination.exists(): return paramiko.SFTP_FAILURE
            source.rename(destination); return paramiko.SFTP_OK
        except OSError as e: return paramiko.SFTPServer.convert_errno(e.errno)

class Fixture:
    def __init__(self, root, host_key, client_key):
        self.metrics = pathlib.Path(root).parent / "authentication-methods.log"; self.metrics.write_text("")
        self.root = root; self.key = paramiko.ECDSAKey.from_private_key_file(str(host_key)); self.client = paramiko.ECDSAKey.from_private_key_file(str(client_key))
        self.socket = socket.socket(); self.socket.bind(("127.0.0.1", 0)); self.socket.listen(); self.socket.settimeout(.2)
        self.port = self.socket.getsockname()[1]; self.stop = threading.Event(); self.transports = []
        self.thread = threading.Thread(target=self.run, daemon=True); self.thread.start()
    def run(self):
        while not self.stop.is_set():
            try: connection, _ = self.socket.accept()
            except socket.timeout: continue
            except OSError: break
            transport = paramiko.Transport(connection); self.transports.append(transport)
            transport.add_server_key(self.key); transport.set_subsystem_handler("sftp", paramiko.SFTPServer, Files, root=str(self.root))
            try: transport.start_server(server=Authentication(self.client,self.metrics))
            except (paramiko.SSHException, EOFError): transport.close()
    def close(self):
        self.stop.set(); self.socket.close()
        for transport in self.transports: transport.close()
        self.thread.join(timeout=2)
