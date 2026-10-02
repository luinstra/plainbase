package com.plainbase.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifySharedBuildVersionTest {
    @TempDir
    lateinit var root: Path

    private fun launcher(version: String, mode: String = "serve"): Path {
        val program = root.resolve("server.py")
        Files.writeString(
            program,
            """
            import http.server, os, pathlib, time
            pathlib.Path('${root.resolve("pid")}').write_text(str(os.getpid()))
            mode = '$mode'
            if mode == 'exit':
                raise SystemExit(17)
            if mode == 'timeout':
                time.sleep(120)
            if mode == 'bind':
                print('BindException', flush=True)
                time.sleep(120)
            class Handler(http.server.BaseHTTPRequestHandler):
                def do_GET(self):
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{"version":"$version"}')
                def log_message(self, *args):
                    pass
            server = http.server.HTTPServer(('127.0.0.1', int(os.environ['PLAINBASE_PORT'])), Handler)
            print('Responding at http://127.0.0.1:' + os.environ['PLAINBASE_PORT'], flush=True)
            server.serve_forever()
        """.trimIndent(),
        )
        val launcher = root.resolve("plainbase")
        Files.writeString(launcher, "#!/bin/sh\nexec python3 '$program'\n")
        check(launcher.toFile().setExecutable(true))
        return launcher
    }

    private fun assertReaped() {
        val pid = Files.readString(root.resolve("pid")).toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `should execute snapshot and release launchers and always reap the child`() {
        listOf("0.1.0-SNAPSHOT", "1.2.3-rc.1").forEach { version ->
            VerifySharedBuildVersion.verifyLauncher(launcher(version), version, 5)
            assertReaped()
        }
    }

    @Test
    fun `should reject wrong version startup failure timeout and bind failure and reap the child`() {
        val diagnostics = listOf(
            "serve" to "Installed launcher version mismatch: expected 0.0.0-ci, got wrong",
            "exit" to "Installed launcher exited during version verification:",
            "timeout" to "Installed launcher health/version verification timed out:",
            "bind" to "Installed launcher failed to bind during version verification",
        )
        diagnostics.forEach { (mode, diagnostic) ->
            val error = assertFailsWith<IllegalStateException> {
                VerifySharedBuildVersion.verifyLauncher(launcher("wrong", mode), "0.0.0-ci", if (mode == "timeout") 1 else 10)
            }
            assertTrue(error.message.orEmpty().contains(diagnostic), "$mode: ${error.message}")
            assertReaped()
        }
    }
}
