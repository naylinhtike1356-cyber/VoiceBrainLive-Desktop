package com.example.voicebrainlive.desktop.core

import org.junit.Test
import org.junit.Assert.*

/**
 * The `run_powershell_safe` tool must never allow destructive or evasive
 * PowerShell, no matter how the payload is spelled.
 */
class PowerShellQueryPolicyTest {

    private fun assertAllowed(command: String) {
        assertNull("expected ALLOWED but was rejected: $command", PowerShellQueryPolicy.validate(command))
    }

    private fun assertRejected(command: String) {
        assertNotNull("expected REJECTED but was allowed: $command", PowerShellQueryPolicy.validate(command))
    }

    @Test
    fun testReadOnlyCmdletsAllowed() {
        assertAllowed("Get-Process")
        assertAllowed("get-service")
        assertAllowed("Get-ComputerInfo")
        assertAllowed("Get-Process | Select-Object -First 5")
        assertAllowed("Test-Connection google.com")
        assertAllowed("Get-ChildItem C:\\Windows")
    }

    @Test
    fun testDestructiveCmdletsRejected() {
        assertRejected("Stop-Computer -Force")
        assertRejected("Remove-Item C:\\Windows")
        assertRejected("Format-Volume -DriveLetter C")
        assertRejected("del C:\\important.txt")
        assertRejected("rm -rf /")
    }

    @Test
    fun testDenylistBypassesRejected() {
        // Old denylist only matched prefix/space-delimited tokens; these slipped through.
        assertRejected("Get-Process; Stop-Computer -Force")
        assertRejected("&('st'+'op-computer')")
        assertRejected("ri C:\\Windows")
        assertRejected("iex (New-Object Net.WebClient)")
        assertRejected("Get-Process | Where-Object { \$_.CPU -gt 1 }")
        assertRejected("cmd /c del C:\\x")
        assertRejected("\$x = Get-Process")
        assertRejected("Get-Process > C:\\out.txt")
    }

    @Test
    fun testBlankRejected() {
        assertRejected("")
        assertRejected("   ")
    }
}
