package dev.digiwomb.unboundair.scanner

/*
 * Scanner error types (SC-05).
 *
 * The scanner client raises one of the ScannerException subclasses below
 * for each kind of scanner failure, so callers (CLI commands, the service
 * loop) can react to each failure individually instead of parsing generic
 * socket or I/O exceptions.
 */

/**
 * Base class of all scanner error types.
 *
 * The hierarchy is sealed: the device has a fixed set of failure states, so
 * the set of error types is deliberately closed.
 *
 * @param message a human-readable description of the failure.
 * @param cause the underlying cause, if any (e.g. the socket exception that
 *   made the scanner appear offline).
 */
sealed class ScannerException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Cannot reach the scanner.
 *
 * Raised when a connection cannot be established (host unreachable, refused,
 * or down) or when an established connection drops in the middle of an
 * operation.
 */
final class ScannerOfflineException(
    message: String,
    cause: Throwable? = null,
) : ScannerException(message, cause)

/**
 * Scanner busy.
 *
 * Raised when the scanner answers `devbusy`, i.e. it is still busy with the
 * previous operation and cannot start the next one yet.
 */
final class ScannerBusyException(
    message: String,
    cause: Throwable? = null,
) : ScannerException(message, cause)

/**
 * No paper.
 *
 * Raised when the scanner answers `nopaper` at a point where a page was
 * expected.
 */
final class ScannerNoPaperException(
    message: String,
    cause: Throwable? = null,
) : ScannerException(message, cause)

/**
 * Battery low.
 *
 * Raised when the scanner answers `battlow`.
 */
final class ScannerBatteryLowException(
    message: String,
    cause: Throwable? = null,
) : ScannerException(message, cause)

/**
 * Protocol error.
 *
 * Raised on a protocol violation: an unexpected or unrecognised response, a
 * wrong prefix, or a malformed `jpegsize` answer.
 */
final class ScannerProtocolException(
    message: String,
    cause: Throwable? = null,
) : ScannerException(message, cause)

/**
 * Timeout.
 *
 * Raised when a socket read or another operation on the scanner connection
 * times out before the expected data arrived.
 */
final class ScannerTimeoutException(
    message: String,
    cause: Throwable? = null,
) : ScannerException(message, cause)
