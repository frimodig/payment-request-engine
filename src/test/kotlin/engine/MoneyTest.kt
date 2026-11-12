package engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun `parses exactly using the currency's exponent`() {
        assertEquals(Money(1234, "EUR"), Money.parse("12.34", "EUR"))
        assertEquals(Money(1200, "EUR"), Money.parse("12", "EUR"))
        assertEquals(Money(1250, "EUR"), Money.parse("12.5", "EUR"))
        assertEquals(Money(500, "JPY"), Money.parse("500", "JPY"))
        assertEquals(Money(1500, "KWD"), Money.parse("1.5", "KWD"))
    }

    @Test
    fun `trailing zeros beyond the exponent are still exact`() {
        assertEquals(Money(1250, "EUR"), Money.parse("12.500", "EUR"))
        assertEquals(Money(100, "JPY"), Money.parse("100.00", "JPY"))
    }

    @Test
    fun `an amount that needs rounding is rejected, never rounded`() {
        assertFailsWith<IllegalArgumentException> { Money.parse("12.345", "EUR") }
        assertFailsWith<IllegalArgumentException> { Money.parse("0.5", "JPY") }
    }

    @Test
    fun `amounts must be positive`() {
        assertFailsWith<IllegalArgumentException> { Money.parse("0", "EUR") }
        assertFailsWith<IllegalArgumentException> { Money.parse("0.00", "EUR") }
        assertFailsWith<IllegalArgumentException> { Money.parse("-1.00", "EUR") }
        assertFailsWith<IllegalArgumentException> { Money(0, "EUR") }
        assertFailsWith<IllegalArgumentException> { Money(-5, "EUR") }
    }

    @Test
    fun `only plain decimal strings are accepted`() {
        for (bad in listOf("", " 1.00", "1.00 ", "1e2", "+1.00", "1,000.00", "1,00", ".5", "5.", "NaN", "0x10")) {
            assertFailsWith<IllegalArgumentException>("'$bad'") { Money.parse(bad, "EUR") }
        }
    }

    @Test
    fun `currency must be a known ISO 4217 code with a minor unit`() {
        assertFailsWith<IllegalArgumentException> { Money.parse("1.00", "eur") }
        assertFailsWith<IllegalArgumentException> { Money.parse("1.00", "EURO") }
        assertFailsWith<IllegalArgumentException> { Money.parse("1.00", "ZZZ") }
        assertFailsWith<IllegalArgumentException> { Money.parse("1", "XXX") }
        // The constructor checks the same, so a Money read from anywhere else is as valid as a parsed one.
        assertFailsWith<IllegalArgumentException> { Money(1, "ZZZ") }
        assertFailsWith<IllegalArgumentException> { Money(1, "XXX") }
    }

    @Test
    fun `largest representable amount parses and one more minor unit is rejected`() {
        assertEquals(Money(Long.MAX_VALUE, "EUR"), Money.parse("92233720368547758.07", "EUR"))
        assertFailsWith<IllegalArgumentException> { Money.parse("92233720368547758.08", "EUR") }
    }
}
