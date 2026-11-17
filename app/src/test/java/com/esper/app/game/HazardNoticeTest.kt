package com.esper.app.game

import com.esper.app.core.ServerError
import com.esper.app.core.Unauthorized
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class HazardNoticeTest {
    private val unsafe = "Can't check the area is safe right now, so no encounter yet."

    @Test fun outsideCoverageSaysSo() = assertEquals(
        "Encounters aren't available in this area yet.",
        hazardFailureNotice(ServerError(404, "outside_coverage", "Outside coverage.")),
    )

    @Test fun everyOtherFailureFailsClosed() {
        listOf(
            ServerError(503, "hazards_unavailable", "Unavailable."),
            ServerError(404, "", "The Esper server returned 404."),
            Unauthorized(),
            IOException("Can't reach the Esper server."),
            IllegalStateException("Overpass error: runtime error"),
        ).forEach { assertEquals(it.toString(), unsafe, hazardFailureNotice(it)) }
    }
}
