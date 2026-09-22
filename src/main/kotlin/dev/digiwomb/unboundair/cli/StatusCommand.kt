package dev.digiwomb.unboundair.cli

import dev.digiwomb.unboundair.scanner.ScannerClient

/**
 * The `status` command: reports the scanner's status and firmware version.
 */
class StatusCommand(
    private val client: ScannerClient,
) {
    /**
     * Queries the scanner's status and firmware version.
     *
     * Status and firmware are fetched in two separate connections (SC-02:
     * one operation, one connection). Failures are not handled here; they
     * propagate to the caller.
     */
    fun run(): String {
        val status = client.queryStatus()
        val firmware = client.fetchFirmwareVersion()
        return "Status: $status\nFirmware: $firmware"
    }
}
