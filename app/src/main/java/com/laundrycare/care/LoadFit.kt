package com.laundrycare.care

import com.laundrycare.data.GarmentKind
import kotlin.math.floor

/** Turning "⅓ drum" into something countable. Rough by design; the UI says so. */
object LoadFit {

    /** How many of `kind` fit in `frac` of a drum, limited by space and (where published) weight. */
    fun count(drumLiters: Int, maxKg: Double?, wetFactor: Double, frac: LoadFraction, kind: GarmentKind): Int {
        if (frac == LoadFraction.ONE_ITEM) return 1
        val byVolume = floor(drumLiters * frac.value / (kind.liters * wetFactor)).toInt()
        val byWeight = maxKg?.let { floor(it * 1000 * frac.value / kind.grams).toInt() } ?: Int.MAX_VALUE
        return maxOf(1, minOf(byVolume, byWeight))
    }

    fun count(washer: Washer, frac: LoadFraction, kind: GarmentKind) =
        count(washer.drumLiters, washer.maxKg, 1.0, frac, kind)

    fun count(dryer: Dryer, frac: LoadFraction, kind: GarmentKind) =
        count(dryer.drumLiters, dryer.maxKg, dryer.wetFactor, frac, kind)

    /** "about 6 of these", for the garment being looked at. */
    fun text(n: Int, frac: LoadFraction): String = when {
        frac == LoadFraction.ONE_ITEM -> "one at a time"
        n == 1 -> "about 1 of these"
        else -> "about $n of these"
    }

    /** A short reference for everything else, so a mixed load can be judged. */
    val REFERENCE = listOf(GarmentKind.TSHIRT, GarmentKind.SHIRT, GarmentKind.JUMPER, GarmentKind.JEANS, GarmentKind.TOWEL)
}
