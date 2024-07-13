import java.io.File
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.stream.Collectors
import java.util.stream.IntStream
import kotlin.collections.ArrayDeque
import kotlin.math.roundToInt


var startCollectTs = 0L

const val THREAD_COUNT = 8
val executor = Executors.newFixedThreadPool(THREAD_COUNT)

fun main(args: Array<String>) {
    println("Started ${Date()}")
    apiToken = args.first()
    println(args.joinToString { it })

    while (true) {
        runCatching { doLoop() }
            .onFailure {
                Thread.sleep(1000)
                System.err.println("doLoop failed " + it.stackTraceToString())
            }
    }
}

private fun doLoop() {

    // Api.resetRound(); System.exit(1)

    val roundInfo = Api.getRoundInfo()

    val roundName = roundInfo.rounds?.firstOrNull { it.isCurrent }?.name ?: "unknown-round"

    // load visited planets from file from file system "$ROUND_NAME.txt"
    visitedPlanets.clear()
    val file = File("$roundName.txt")
    if (file.exists()) {
        file.readLines().forEach {
            visitedPlanets.add(it)
        }
    }
    visitedPlanets.add("Eden")
    visitedPlanets.add("Earth")


    while (true) {
        log("get universe")
        val universe = Api.getUniverse()

        SHIP_WIDTH = universe.ship!!.capacityX
        SHIP_HEIGHT = universe.ship!!.capacityY

        logMap("current cargo", universe.ship!!.garbage!!)

        val planetsToTravel = getPlanetsToTravel(universe).toMutableList()

        log("myPlanetIs=${universe.ship!!.planet!!.name!!}", "planetsToTravel", planetsToTravel)

        val finalPlanetsPath = planetsToTravel.also { it.removeFirst() }

        if (finalPlanetsPath.isEmpty()) {
            log("no planets to travel, sleep")
            Thread.sleep(1000)
            throw RuntimeException("no planets to travel, restart, wait for new round")
        }

        val planetInfo = Api.travel(finalPlanetsPath)

        if (planetInfo.planetGarbage!!.isEmpty()) {
            saveVisitedPlanets(roundName)
            log("no garbage on planet, go next")
            Thread.sleep(500)
            continue
        }

        startCollectTs = System.currentTimeMillis()

        val maxGarbage = findBestLoadOutFacade(planetInfo)

        val occupied = maxGarbage.values.map { it.size }.sum()
        val maxOccupied = SHIP_HEIGHT * SHIP_WIDTH
        val ratioToCargo = occupied.toDouble() / maxOccupied
        val ratioToPlanet = occupied.toDouble() / planetInfo.planetGarbage!!.values.map { it.size }.sum()
        val planetMaxOccupied = planetInfo.planetGarbage!!.values.map { it.size }.sum()
        logMap("maxGarbage occupied $occupied from $maxOccupied ratioToCargo=$ratioToCargo, planetMaxOccupied=$planetMaxOccupied ratioToPlanet=$ratioToPlanet", maxGarbage)

        if (maxOccupied > occupied) {
            log("there still garbage at='$planetsToTravel' removeIt from visited planets")
            visitedPlanets.remove(planetsToTravel.last())
        }
        saveVisitedPlanets(roundName)

        runCatching {
            Api.collect(maxGarbage)
        }.onFailure {

            val shouldResetVisitedPlanets = !(it.message?.contains("Bad Gateway") == true)
            if (shouldResetVisitedPlanets) {
                visitedPlanets.clear()
                saveVisitedPlanets(roundName)
                visitedPlanets.add("Eden")
                visitedPlanets.add("Earth")
            }
            throw it
        }

        log("collected")

        Thread.sleep(500)
        // break
    }
}

private fun findBestLoadOutFacade(planetInfo: PlanetInfo): Map<String, List<List<Int>>> {
    val futures = IntStream.range(0, THREAD_COUNT)
        .mapToObj { executor.submit(Callable { findBestLoadOut(planetInfo.deepCopy()) }) }
        .collect(Collectors.toList())

    val maxGarbages = futures.stream()
        .map { it.get() }
        .collect(Collectors.toList())

    val maxGarbage = maxGarbages.maxBy { it.values.map { it.size }.sum() }
    return maxGarbage
}

fun saveVisitedPlanets(roundName: String) {
    val file = File("$roundName.txt")
    file.absoluteFile.let { println("file path " + it) }
    file.writeText(visitedPlanets.joinToString("\n"))
}

/**
 * output is like
 * . . . G
 * . . G G
 * . . G G
 *
 * size of ship is WIDTH HEIGHT from constants
 */
fun logMap(label: String, garbage: Map<String, List<List<Int>>>) {
    val ship = Array(SHIP_HEIGHT) { Array(SHIP_WIDTH) { '.' } }

    garbage.forEach { (garbageId, positions) ->
        positions.forEach {
            val x = it[0]
            val y = it[1]
            ship[y][x] = 'G'
        }
    }

    println(label)
    ship.forEach {
        println(it.joinToString(" "))
    }
}

fun findBestLoadOut(planetInfo: PlanetInfo): Map<String, List<List<Int>>> {
    var bestLoadout: MutableMap<String, List<List<Int>>> = mutableMapOf()

    val planetMaxSum = planetInfo.planetGarbage!!.values.map { it.size }.sum()

    var count = 0
    var countUpgrades = 0
    while (true) {
        count++
        val pair = doRandomSearch(planetInfo)
        val candidate_bestLoadout = pair.first

        val newSum = candidate_bestLoadout.values.map { it.size }.sum()
        val prevSum2 = bestLoadout.values.map { it.size }.sum()
        if (newSum > prevSum2) {
            countUpgrades++
            log("findBestLoadOut currentOccupy prev=${prevSum2} newSum=${newSum}")
            bestLoadout = candidate_bestLoadout
        }

        if (newSum == planetMaxSum) {
            log("all garbage collected")
            break
        }

        if (System.currentTimeMillis() - startCollectTs > 3000) {
            break
        }
    }

    log("findBestLoadOut FINAL variantsChecked=${count} countUpgrades=$countUpgrades currentOccupy")
    logNotPretty("findBestLoadOut took=${System.currentTimeMillis() - startCollectTs} bestLoadout", bestLoadout)

    return bestLoadout
}

private fun doRandomSearch(planetInfo: PlanetInfo): Pair<MutableMap<String, List<List<Int>>>, BooleanPlainArray> {
    val shipInitialOccupy = planetInfo.richShipGarbage.occupyArray

    val bestLoadout = mutableMapOf<String, List<List<Int>>>()

    val queue = ArrayDeque(planetInfo.richPlanetGarbage.listOfRichGarabge)
    Collections.shuffle(queue)
    queue.forEach { garbage ->
        val rotation = (Math.random() * 4).toInt()

        garbage.applyRotation(rotation)
    }

    var currentOccupy = shipInitialOccupy.copy()

    while (queue.isNotEmpty()) {
        val candidate = queue.removeFirst()

        val candidateOccupy = candidate.occupyArray

        var breakRepeat = false
        repeat(SHIP_WIDTH) { shiftX ->
            if (breakRepeat) {
                return@repeat
            }
            repeat(SHIP_HEIGHT) { shiftY ->
                if (breakRepeat) {
                    return@repeat
                }
                if (canBeApplied(currentOccupy, candidateOccupy, shiftX, shiftY)) {
                    currentOccupy = applyOccupy(currentOccupy, candidateOccupy, shiftX, shiftY)
                    val points: List<List<Int>> = candidate.pointsAsList
                    bestLoadout[candidate.garbageId] = points.map {
                        listOf(it[0] + shiftX, it[1] + shiftY)
                    }
                    breakRepeat = true

                    //  logNotPretty("add to bestLoadout =${candidate.garbageId} shiftX=$shiftX shiftY=$shiftY cells=", bestLoadout)
                    return@repeat
                }
            }
        }
    }
    return Pair(bestLoadout, currentOccupy)
}

fun logMap(label: String, garbage: BooleanPlainArray) {
    val ship = Array(garbage.cellsHeight) { Array(garbage.cellsWidth) { '.' } }

    garbage.fori { x, y, value ->
        ship[y][x] = if (value) 'G' else '.'
    }

    println(label)
    ship.forEach {
        println(it.joinToString(" "))
    }
}

fun canBeApplied(first: BooleanPlainArray, second: BooleanPlainArray, shiftX: Int, shiftY: Int): Boolean {
    var result = true

    second.fori { x, y, value ->
        if (value) {
            val x1 = x + shiftX
            val y1 = y + shiftY

            if (x1 < 0 || x1 >= first.cellsWidth || y1 < 0 || y1 >= first.cellsHeight) {
                result = false
                return@fori
            }

            if (first.getFast(x1, y1)) {
                result = false
                return@fori
            }
        }
    }

    return result
}

// apply second to first without copy, apply shift as well
fun applyOccupy(first: BooleanPlainArray, second: BooleanPlainArray, shiftX: Int, shiftY: Int): BooleanPlainArray {

    second.fori { x, y, value ->
        if (value) {
            val x1 = x + shiftX
            val y1 = y + shiftY

            first.setFast(x1, y1, true)
        }
    }

    return first

}

private val EDEN_PLANET = "Eden"

private fun getPlanetsToTravel(universe: UniverseDto): List<String> {

    val shipHasGarbage = universe.ship!!.garbage!!.isNotEmpty()

    val currentPlanet = universe.ship!!.planet

    val occupied = universe.ship!!.garbage!!.values.map { it.size }.sum()
    val totalSpace = SHIP_HEIGHT * SHIP_WIDTH

    val occupiedRatio = occupied.toDouble() / totalSpace

    val targetPlanet = if (shipHasGarbage && occupiedRatio > 0.4) {
        log("getPlanetsToTravel go to empty garbage on Eden")
        EDEN_PLANET
    } else {

        findClosestPlanetWithConditionBfs(universe, currentPlanet!!.name!!).also {
            visitedPlanets.add(it)
        }.also {
            log("getPlanetsToTravel go to closest not visited planet with garbage $it ${if (occupiedRatio > 0.0001) " SHIP HAS SOME GARBAGE ration=${occupiedRatio}" else ""}")
        }
    }

    // find path to target planet by bfs
    val path = aStarSearch(universe, currentPlanet!!.name!!, targetPlanet)

    return path
}

val visitedPlanets = mutableSetOf<String>("Eden", "Earth")

fun findClosestPlanetWithConditionBfs(universe: UniverseDto, startPlanet: String): String {

    val graph = mutableMapOf<String, List<String>>()

    universe.universe!!.forEach {
        val from = it[0] as String
        val to = it[1] as String
        val cost = (it[2] as Double).roundToInt()

        graph[from] = graph.getOrDefault(from, listOf()) + to
        graph[to] = graph.getOrDefault(to, listOf()) + from
    }

    val visited = mutableSetOf<String>()
    val queue = mutableListOf(listOf(startPlanet))


    while (queue.isNotEmpty()) {
        val path = queue.removeAt(0)
        val node = path.last()

        if (node != startPlanet && !visitedPlanets.contains(node)) {
            return node
        }

        if (visited.contains(node)) {
            continue
        }

        visited.add(node)

        graph[node]?.forEach {
            if (!visited.contains(it)) {
                queue.add(path + it)
            }
        }
    }

    return "Eden"
}

/**
 * take to account cost of move
 */
fun aStarSearch(universe: UniverseDto, currentPlanet: String, targetPlanet: String): List<String> {

    val graph = mutableMapOf<String, List<String>>()

    universe.universe!!.forEach {
        val from = it[0] as String
        val to = it[1] as String
        val cost = (it[2] as Double).roundToInt()

        graph[from] = graph.getOrDefault(from, listOf()) + to
        //   graph[to] = graph.getOrDefault(to, listOf()) + from
    }

    val visited = mutableSetOf<String>()
    val queue = mutableListOf(listOf(currentPlanet))

    while (queue.isNotEmpty()) {
        val path = queue.removeAt(0)
        val node = path.last()

        if (node == targetPlanet) {
            return path
        }

        if (visited.contains(node)) {
            continue
        }

        visited.add(node)

        graph[node]?.forEach {
            if (!visited.contains(it)) {
                queue.add(path + it)
            }
        }
    }

    return listOf()
}

fun log(vararg any: Any?) {
    any.joinToString {
        // to json
        when (it) {
            is String -> it
            else -> Api.gsonPretty.toJson(it ?: "null")
        }
    }.let {
        println(Date().toString() + ">" + it)
    }
}

fun logNotPretty(vararg any: Any?) {
    any.joinToString {
        // to json
        when (it) {
            is String -> it
            else -> Api.gson.toJson(it ?: "null")
        }
    }.let {
        println(Date().toString() + ">" + it)
    }
}

