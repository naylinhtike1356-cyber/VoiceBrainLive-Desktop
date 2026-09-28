package com.example.voicebrainlive.desktop.core

/**
 * Allowlist policy for the `run_powershell_safe` voice/model tool.
 *
 * A denylist can never be made safe against PowerShell — aliases (`ri`, `%`),
 * string concatenation (`'st'+'op-computer'`), the `&` call operator, `cmd /c`,
 * indirectly-spelled `Invoke-Expression`, etc. all bypass token matching.
 * This policy therefore allows ONLY known read-only cmdlets, and rejects any
 * command containing characters that can smuggle a second statement, a
 * subexpression, variable expansion, invocation, or redirection.
 *
 * Pure logic (no process launching) so it is unit-testable on any platform.
 */
object PowerShellQueryPolicy {

    val allowedCmdlets = setOf(
        "get-process", "get-service", "get-computerinfo", "get-date",
        "get-childitem", "get-content", "get-location", "get-psdrive",
        "test-connection", "get-netipconfiguration", "get-hotfix",
        "get-culture", "get-clipboard", "get-random"
    )

    private val dangerousChars = setOf(
        ';', '$', '`', '(', ')', '{', '}', '&',
        '<', '>', '"', '\'', '@', '#', '=', '!', ','
    )

    /**
     * Returns `null` when [command] is allowed, otherwise a Burmese rejection
     * reason suitable for speaking back to the user.
     */
    fun validate(command: String): String? {
        val clean = command.trim()
        if (clean.isEmpty()) {
            return "PowerShell အမိန့် ဗလာဖြစ်နေပါတယ်ရှင်။"
        }
        val firstToken = clean.split(Regex("\\s+"), limit = 2).firstOrNull()
            ?.lowercase()?.trimStart('&', '.', '/', '\\') ?: ""
        if (firstToken !in allowedCmdlets) {
            return "လုံခြုံရေးအရ ဤ PowerShell cmdlet ကို တိုက်ရိုက် run ခွင့်မပြုပါရှင်။ " +
                "ခွင့်ပြုထားသော read-only cmdlet များသာ သုံးနိုင်ပါတယ်ရှင်: " +
                allowedCmdlets.sorted().joinToString(", ")
        }
        val badChar = clean.firstOrNull { it in dangerousChars }
        if (badChar != null) {
            return "လုံခြုံရေးအရ ဤ PowerShell အမိန့်တွင် အန္တရာယ်ရှိနိုင်သော '$badChar' " +
                "ပါဝင်သဖြင့် run ခွင့်မပြုပါရှင်။"
        }
        return null
    }
}
