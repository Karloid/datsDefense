class BooleanPlainArray(val cellsWidth: Int, val cellsHeight: Int) {

    private val array: BooleanArray = BooleanArray(cellsWidth * cellsHeight)

    operator fun get(x: Int, y: Int): Boolean? =
        if (inBounds(x, y)) array[y * cellsWidth + x] else null

    fun getFast(x: Int, y: Int): Boolean = array[y * cellsWidth + x]

    fun set(x: Int, y: Int, value: Boolean) {
        if (inBounds(x, y)) array[y * cellsWidth + x] = value
    }

    fun setFast(x: Int, y: Int, value: Boolean) {
        array[y * cellsWidth + x] = value
    }

    private fun inBounds(x: Int, y: Int): Boolean =
        x in 0 until cellsWidth && y in 0 until cellsHeight

    fun fori(block: (x: Int, y: Int, v: Boolean) -> Unit) {
        for (y in 0 until cellsHeight) {
            for (x in 0 until cellsWidth) {
                block(x, y, getFast(x, y))
            }
        }
    }

    fun clear() {
        array.fill(false)
    }

    fun filter(function: (Boolean) -> Boolean): List<Boolean> {
        val result = mutableListOf<Boolean>()
        fori { _, _, value ->
            if (function(value)) {
                result.add(value)
            }
        }
        return result
    }

    fun toListOfArrayPoints(): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        fori { x, y, value ->
            if (value) {
                result.add(listOf(x, y))
            }
        }
        return result
    }

    fun copy(): BooleanPlainArray {
        val result = BooleanPlainArray(cellsWidth, cellsHeight)
        fori { x, y, value ->
            result.setFast(x, y, value)
        }
        return result
    }

}