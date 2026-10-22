package squareup.domain.money

import kotlin.random.Random

data class Money(val kopeck: UInt) {

    fun splitEvenly(number: Int): List<Money> {
        require (number > 0) { "Number of shares must be positive." }
        val share = kopeck / number.toUInt()
        val result = MutableList(number) { share }

        var rem = this % number
        while (rem > 0u) {
            val random = Random.nextInt(result.size)
            if (result[random] == share) {
                result[random]++
                rem--
            }
        }
        return result.map {Money(it)}
    }

    operator fun plus(other: Money): Money {
        if (UInt.MAX_VALUE - other.kopeck < kopeck) throw ArithmeticException("Kopeck addition overflow: $kopeck + ${other.kopeck}")
        return Money(kopeck + other.kopeck)
    }

    operator fun minus(other: Money): Money {
        if (other.kopeck > kopeck) throw ArithmeticException("Cannot subtract a greater value ${other.kopeck} from $kopeck")
        return Money(kopeck - other.kopeck)
    }

    operator fun times(number: Int): Money {
        require (number >= 0) { "Multiplier must not be negative." }
        if (number == 0) return Money(0u)
        if (UInt.MAX_VALUE / number.toUInt() < kopeck) throw ArithmeticException("Kopeck multiplication overflow for $kopeck and $number")
        return Money(kopeck.times(number.toUInt()))
    }

    operator fun div(number: Int): Money {
        require (number > 0) { "Cannot divide by a non-positive number" }
        return Money(kopeck / number.toUInt())
    }

    operator fun rem(number: Int): UInt {
        require (number > 0) { "Cannot rem by a non-positive number" }
        return kopeck % number.toUInt()
    }

}
