package com.nabd.edge;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * ACC-040/042: the second shared Spring context, with the real booking flow switched on (the first one keeps the counting test flow on
 * State.START). Region "bk" gives it its own event streams, so the two cached contexts never compete for the same entries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "edge.booking-flow=true", "edge.region=bk", "edge.emergency-numbers.bk=108", "edge.languages.bk=en,hi,kn,te",
        "edge.emergency-phrases=chest pain,severe bleeding"})
abstract class BookingTestBase extends EdgeTestBase {
}
