package engine

import java.math.BigDecimal
import java.util.Currency

/** Integer minor units plus ISO 4217 currency. No floating point anywhere. */
data class Money(val minor: Long, val currency: String) {
    init {
        require(minor > 0) { "Amount must be positive, was $minor" }
        require(minorUnitExponent(currency) >= 0) { "Currency $currency has no minor unit" }
    }

    companion object {
        /** Throws for anything that is not a known ISO 4217 code. */
        private fun minorUnitExponent(currency: String): Int {
            require(currency.matches(CURRENCY_CODE)) { "Currency must be three upper-case letters, was '$currency'" }
            return Currency.getInstance(currency).defaultFractionDigits
        }

        private val CURRENCY_CODE = Regex("[A-Z]{3}")

        // Plain decimal digits only: no sign, no exponent, no spaces, no thousands separators.
        private val PLAIN_DECIMAL = Regex("[0-9]+(\\.[0-9]+)?")

        /**
         * Parses an amount string exactly. An amount with more decimals than the currency allows is
         * rejected, never rounded: the requested amount is exact or refused.
         */
        fun parse(amount: String, currency: String): Money {
            require(amount.matches(PLAIN_DECIMAL)) { "Not a plain decimal amount: '$amount'" }
            val exponent = minorUnitExponent(currency)
            require(exponent >= 0) { "Currency $currency has no minor unit" }

            val value = BigDecimal(amount).stripTrailingZeros()
            require(value.scale() <= exponent) { "$currency allows $exponent decimals, got '$amount'" }
            val minor = try {
                value.movePointRight(exponent).longValueExact()
            } catch (e: ArithmeticException) {
                throw IllegalArgumentException("Amount out of range: '$amount'", e)
            }
            return Money(minor, currency)
        }
    }
}
