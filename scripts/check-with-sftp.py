#!/usr/bin/env python3
"""Starts a temporary localhost Paramiko SFTP server and runs real transport tests.
Requires JDK 17, Gradle wrapper, ssh-keygen and paramiko. No permanent SSH changes or privileged server needed.
"""
import base64, hashlib, os, pathlib, socket, subprocess, tempfile, time

project = pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="folio-sftp-") as tmp:
    root = pathlib.Path(tmp)
    books = root / "books"
    books.mkdir()
    for name in ("host", "client"):
        subprocess.run(["ssh-keygen", "-q", "-t", "ecdsa", "-m", "PEM", "-N", "", "-f", str(root / name)], check=True)
    (root / "authorized_keys").write_bytes((root / "client.pub").read_bytes())
    blob = base64.b64decode((root / "host.pub").read_text().split()[1])
    fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip("=")
    from sftp_fixture import Fixture
    server = Fixture(books, root / "host", root / "client")
    env = dict(os.environ, FOLIO_SFTP_PORT=str(server.port), FOLIO_SFTP_ROOT=str(books), FOLIO_SFTP_FINGERPRINT=fingerprint, FOLIO_SFTP_KEY=str(root / "client"), FOLIO_SFTP_AUTH_LOG=str(server.metrics))
    try:
        command = [os.environ.get("FOLIO_GRADLE_EXECUTABLE", str(project / "gradlew")), "--no-daemon", "checkFormatting", ":core:test", ":transport:test", ":app:testDebugUnitTest", ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":app:lintDebug"]
        # A per-process proxy is needed only in the hosted build environment.
        if os.environ.get("FOLIO_USE_ENV_PROXY"):
            from urllib.parse import urlparse
            proxy = urlparse(os.environ["HTTPS_PROXY"])
            command[1:1] = [f"-Dhttps.proxyHost={proxy.hostname}", f"-Dhttps.proxyPort={proxy.port}", f"-Dhttp.proxyHost={proxy.hostname}", f"-Dhttp.proxyPort={proxy.port}", "-Dhttp.nonProxyHosts=localhost|127.*"]
        subprocess.run(command, cwd=project, env=env, check=True)
    finally:
        server.close()
