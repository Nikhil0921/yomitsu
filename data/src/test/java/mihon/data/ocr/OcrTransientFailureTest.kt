package mihon.data.ocr

import io.kotest.matchers.shouldBe
import mihon.domain.ocr.exception.OcrException.ConnectionError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Measured on hardware 2026-09-28: over a 19-page read-aloud the GLENS escalation produced 15
 * network failures — 13 `SocketTimeoutException: timeout` and 2 `ConnectionError` — and **neither
 * retry line fired even once**, while 45 tile responses completed and 0 pages produced a result.
 * The cause: the retry predicate matched only messages containing "HTTP 5" or "HTTP 429", so a
 * read timeout (whose message is literally "timeout") was never classified as transient.
 */
@Execution(ExecutionMode.CONCURRENT)
class OcrTransientFailureTest {

    @Test
    fun `a read timeout is transient`() {
        isTransientOcrFailure(SocketTimeoutException("timeout")) shouldBe true
    }

    @Test
    fun `a refused connect is transient`() {
        isTransientOcrFailure(ConnectException("Connection refused")) shouldBe true
    }

    @Test
    fun `a wrapped read timeout is transient`() {
        val wrapped = IOException("glens page failed", SocketTimeoutException("timeout"))

        isTransientOcrFailure(wrapped) shouldBe true
    }

    /** The escalation path throws this domain wrapper, so the cause chain has to be walked. */
    @Test
    fun `a ConnectionError wrapping a read timeout is transient`() {
        isTransientOcrFailure(ConnectionError(SocketTimeoutException("timeout"))) shouldBe true
    }

    @Test
    fun `an unresolvable host is transient`() {
        isTransientOcrFailure(UnknownHostException("glens unreachable")) shouldBe true
    }

    @Test
    fun `HTTP 5xx stays transient`() {
        isTransientOcrFailure(IOException("HTTP 502 Bad Gateway")) shouldBe true
    }

    @Test
    fun `HTTP 429 stays transient`() {
        isTransientOcrFailure(IOException("HTTP 429 Too Many Requests")) shouldBe true
    }

    @Test
    fun `a 4xx that is not rate limiting is not transient`() {
        isTransientOcrFailure(IOException("HTTP 400 Bad Request")) shouldBe false
    }

    @Test
    fun `a not-found is not transient`() {
        isTransientOcrFailure(IOException("HTTP 404 Not Found")) shouldBe false
    }

    /** Retrying a permanent failure just doubles the wait the user already sat through. */
    @Test
    fun `an unrelated failure is not transient`() {
        isTransientOcrFailure(IllegalStateException("engine closed")) shouldBe false
    }

    @Test
    fun `a failure with no cause and no message is not transient`() {
        isTransientOcrFailure(RuntimeException()) shouldBe false
    }
}
