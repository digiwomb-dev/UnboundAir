package dev.digiwomb.unboundair.output

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Property tests for [documentName] (AU-05): `scan-YYYYMMDD-HHMMSS.pdf` from an instant in a zone.
 *
 * Zero padding is the point of this file: a hand-picked example passes on 27 days out of 30
 * with a missing pad, and a wide instant range rarely draws a single-digit hour. So the day is
 * always generated 1-9 and the clock from dense small ranges -- a missing pad then fails on
 * most draws, not on a few days a month. Fractional-offset zones (Kolkata +05:30, Kathmandu
 * +05:45, Eucla +08:45) catch a name derived from UTC instead of the given zone.
 *
 * The function under test is pure; nothing touches the network, a file, or the clock (DC-03).
 * Every property runs against a fixed [SEED], so runs are deterministic.
 */
class DocumentNamePropertyTest {
    @Nested
    inner class Shape {
        /** Every name matches `scan-<8 digits>-<6 digits>.pdf` and is exactly 24 characters. */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-05 every name has the scan-YYYYMMDD-HHMMSS-pdf shape`() {
            runBlocking {
                checkAll(PropTestConfig(seed = SEED), PAD_SENSITIVE_INSTANT, NAMED_ZONE) { instant, zone ->
                    val name = documentName(instant, zone)

                    assertThat(name)
                        .`as`("name for %s in %s must match scan-YYYYMMDD-HHMMSS.pdf", instant, zone)
                        .matches("scan-\\d{8}-\\d{6}\\.pdf")
                    assertThat(name.length).`as`("a padded name is always 24 characters: %s", name).isEqualTo(24)
                }
            }
        }

        /**
         * Parsing the name back with the same zone yields the same second. Generated instants
         * carry whole seconds only (built from components, never from epoch millis), so with
         * seconds being all the name keeps, the round trip is exact.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-05 parsing the name back with the same zone yields the same second`() {
            runBlocking {
                checkAll(PropTestConfig(seed = SEED), PAD_SENSITIVE_INSTANT, NAMED_ZONE) { instant, zone ->
                    val parsed = LocalDateTime.parse(documentName(instant, zone), NAME_FORMAT).atZone(zone).toInstant()

                    assertThat(parsed)
                        .`as`("round trip of %s in %s must keep the second", instant, zone)
                        .isEqualTo(instant.truncatedTo(ChronoUnit.SECONDS))
                }
            }
        }
    }

    @Nested
    inner class ZeroPadding {
        /**
         * Single-digit days, hours, minutes, and seconds appear zero-padded. Every draw has
         * all four components below 10, so an unpadded `1` where `01` belongs fails every
         * time -- the one defect this issue exists for cannot hide behind a wide range.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-05 single-digit day hour minute and second are zero-padded`() {
            runBlocking {
                checkAll(PropTestConfig(seed = SEED), ALL_SINGLE_DIGIT_INSTANT, NAMED_ZONE) { instant, zone ->
                    val name = documentName(instant, zone)
                    val local = instant.atZone(zone)

                    assertThat(name)
                        .`as`("local %s in %s must render padded", local.toLocalDateTime(), zone)
                        .isEqualTo(
                            "scan-%04d%02d%02d-%02d%02d%02d.pdf".format(
                                local.year,
                                local.monthValue,
                                local.dayOfMonth,
                                local.hour,
                                local.minute,
                                local.second,
                            ),
                        )
                    assertThat(name.substring(11, 13)).`as`("day must be two digits in %s", name).isEqualTo("%02d".format(local.dayOfMonth))
                }
            }
        }
    }

    @Nested
    inner class Zones {
        /**
         * The name is local time, not UTC. Kathmandu is +05:45 year-round (no DST), so a
         * UTC-derived name misses by 5 h 45 min on every draw -- the minutes alone always differ.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-05 the name is local time not UTC`() {
            runBlocking {
                checkAll(PropTestConfig(seed = SEED), PAD_SENSITIVE_INSTANT) { instant ->
                    val zone = ZoneId.of("Asia/Kathmandu")

                    assertThat(documentName(instant, zone))
                        .`as`("name for %s must render in +05:45, not UTC", instant)
                        .isEqualTo("scan-${instant.atZone(zone).format(DATE_TIME_FORMAT)}.pdf")
                }
            }
        }

        /**
         * The same instant in UTC and Kathmandu yields different names. The pair is chosen
         * for offsets that never coincide (+05:45, no DST); pairs sharing an offset
         * (e.g. Berlin/Paris in winter) correctly give equal names and would fail this claim.
         */
        @OptIn(ExperimentalKotest::class)
        @Test
        fun `AU-05 the same instant in UTC and Kathmandu yields different names`() {
            runBlocking {
                checkAll(PropTestConfig(seed = SEED), PAD_SENSITIVE_INSTANT) { instant ->
                    assertThat(documentName(instant, ZoneId.of("Asia/Kathmandu")))
                        .`as`("local time decides the name, so %s must differ between UTC and +05:45", instant)
                        .isNotEqualTo(documentName(instant, ZoneId.of("UTC")))
                }
            }
        }
    }

    private companion object {
        /** Fixed seed, so that every run generates the same cases (determinism). */
        const val SEED = 119L

        /** Parses `scan-YYYYMMDD-HHMMSS.pdf` back into its local date-time; renders its middle part. */
        val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("'scan-'yyyyMMdd'-'HHmmss'.pdf'")
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /**
         * Named zones instead of `ZoneId.getAvailableZoneIds()`: the full set is large and full
         * of aliases, drowning the fractional offsets that matter. UTC is the reference, Berlin
         * the container default (`TZ`), New York and Chatham cover DST and negative offsets,
         * Kolkata/Kathmandu/Eucla the half- and three-quarter-hour shifts.
         */
        val ZONE_IDS =
            arrayOf("UTC", "Europe/Berlin", "America/New_York", "Asia/Kolkata", "Asia/Kathmandu", "Australia/Eucla", "Pacific/Chatham")

        /** A zone from the reasoned list above, picked by index (integer arbs stay in-domain; cf. the double NaN trap). */
        val NAMED_ZONE: Arb<ZoneId> = Arb.int(0..ZONE_IDS.lastIndex).map { ZoneId.of(ZONE_IDS[it]) }

        /**
         * Instants from padding-hostile components: day always 1-9 (valid in every month),
         * clock full-range so small values still appear on most draws. Whole seconds via UTC,
         * so every value is in-domain and round-trips exactly.
         */
        val PAD_SENSITIVE_INSTANT: Arb<Instant> =
            Arb.bind(
                Arb.int(2000..2030),
                Arb.int(1..12),
                Arb.int(1..9),
                Arb.int(0..23),
                Arb.int(0..59),
                Arb.int(0..59),
            ) { y, m, d, h, min, s ->
                LocalDateTime.of(y, m, d, h, min, s).toInstant(ZoneOffset.UTC)
            }

        /** All four padded components single-digit on every draw (day 1-9, clock 0-9). */
        val ALL_SINGLE_DIGIT_INSTANT: Arb<Instant> =
            Arb.bind(
                Arb.int(2000..2030),
                Arb.int(1..12),
                Arb.int(1..9),
                Arb.int(0..9),
                Arb.int(0..9),
                Arb.int(0..9),
            ) { y, m, d, h, min, s ->
                LocalDateTime.of(y, m, d, h, min, s).toInstant(ZoneOffset.UTC)
            }
    }
}
