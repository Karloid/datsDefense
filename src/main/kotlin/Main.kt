import java.io.File
import java.text.DateFormat
import java.util.*
import java.util.concurrent.Executors


var startCollectTs = 0L

const val THREAD_COUNT = 8
val executor = Executors.newFixedThreadPool(THREAD_COUNT)

lateinit var state: State

fun main(args: Array<String>) {
    println("Started ${Date()}")
    apiToken = args.first()
    println(args.joinToString { it })

    loadState()

    while (true) {
        runCatching { doLoop() }
            .onFailure {
                Thread.sleep(1000)
                System.err.println("doLoop failed " + it.stackTraceToString())
            }
    }
}

fun loadState() {
    File("state.json").takeIf { it.exists() }?.let {
        val parsedState = Api.gson.fromJson(it.readText(), State::class.java)
        state = parsedState
    } ?: run {
        state = State()
    }
}

data class State(var currentRound: String? = "") {
    fun save() {
        File("state.json").writeText(Api.gson.toJson(this))
    }
}

private fun doLoop() {
    val roundsInfo = Api.getRounds()

    val activeRound = roundsInfo.rounds.firstOrNull { it.status == "active" }

    log("my round=${state.currentRound} active round $activeRound")

    val canRegisterTo = activeRound?.takeIf {
        val elapsedTime = System.currentTimeMillis() - it.getStartAsLong()
        log("active round elapsed=${elapsedTime / 1000} sec")

        elapsedTime < 5 * 60 * 1000
    }

    if (activeRound != null && activeRound.name != state.currentRound && canRegisterTo != activeRound) {
        log("active round not match, reset to empty, prevValue=${state.currentRound}")
        state.currentRound = ""
        state.save()
        log("there is active round=$activeRound im not participating in it, time left=${(activeRound.getEndAsLong() - roundsInfo.getNowAsLong()) / 1000} sec}")

        Thread.sleep(10_000)
        return
    }

    // join next round
    if (canRegisterTo != null) {
        runCatching {
            log(
                "join next round nextRound.name=${canRegisterTo.name} start=${canRegisterTo.getStartAsLong().toDate()} now=${
                    roundsInfo.getNowAsLong().toDate()
                } diff=${(canRegisterTo.getStartAsLong() - roundsInfo.getNowAsLong()) / 1000} sec"
            )
            val joinRound = Api.joinRound(canRegisterTo.name)

            canRegisterTo.name.let {
                state.currentRound = it
                state.save()
            }
            log("joined round $joinRound sleep for ${joinRound.startsInSec} sec")
            Thread.sleep(joinRound.startsInSec * 1000L)
            log("after sleep")
        }.onFailure {
            log("join next round failed, but it may be ok  ${it.stackTraceToString()}")
            Thread.sleep(1000)
        }

    }
    if (activeRound?.name != state.currentRound) {
        log("active round not match, reset to empty, prevValue=${state.currentRound} activeRound=$activeRound")
        state.currentRound = ""
        state.save()
        return
    }
    var worldState: WorldState? = null

    while (true) {
        runCatching {
            worldState = Api.getWorldState()
        }.onFailure {
            log("getWorldState failed, retry in  ${it.stackTraceToString()}")
            Thread.sleep(1000)
        }

        if (worldState != null) {
            break
        }
    }

    // play round
    while (true) {
        log("get playRound")
        val worldUnits = Api.getUnits()
        log("current turn ${worldUnits.turn} turnEndsInMs=${worldUnits.turnEndsInMs}")
        val startsTs = System.currentTimeMillis()
        val endTurn = startsTs + worldUnits.turnEndsInMs

        makeTurn(worldUnits, worldState!!)

        val delay = endTurn - System.currentTimeMillis() + 2
        log("sleep for $delay ")
        if (delay > 0) {
            Thread.sleep(delay)
        }
        break
    }
}

fun makeTurn(units: WorldUnits, worldState: WorldState) {
    var myGold = units.player.gold

    val cmd = Command()

    val costBase = 1

    log("my states gold=${myGold} base=${units.base?.size} zombies=${units.zombies?.size} enemyBlocks=${units.enemyBlocks?.size} zpots=${worldState.zpots.size}")

    val headBase = units.base.firstOrNull() { it.isHead }

    val allPossiblePlaces = units.base?.flatMap { b ->
        val pos = b.pos

        pos.neighbors().filter { pos ->
            inBounds(pos)
                    && worldState.zpots.none { it.posEqual(pos) }
                    && units.zombies.none { it.posEqual(pos) }
                    && units.base.none { it.pos.equals(pos) }
                    && units.enemyBlocks.none { it.pos.neighborTo(pos) }

                    && worldState.zpots.none { it.pos.neighborTo(pos) }
                    && units.enemyBlocks.none { it.pos.diagonalNeighborTo(pos) }

        }
    }?.sortedBy {
        headBase?.pos?.euclidianDistance2(it) ?: 0.0
    } ?: emptyList()

    allPossiblePlaces.take(myGold).forEach { posToBuild ->
        cmd.build.add(posToBuild)
    }

    // kill zombies


    units.base.forEach { base ->
        val pos = base.pos

        val range = base.range

        val zombiesInRange = units.zombies.filter { it.pos.euclidianDistance2(pos) <= range * range }.minByOrNull { it.pos.euclidianDistance2(pos) }

        zombiesInRange?.let { z ->
            cmd.attack.add(Command.Attack(base.id, z.pos))
            return@forEach
        }

        val enemyBlock = units.enemyBlocks.filter { it.pos.euclidianDistance2(pos) <= range * range }.minByOrNull { it.pos.euclidianDistance2(pos) }

        enemyBlock?.let { z ->
            cmd.attack.add(Command.Attack(base.id, z.pos))
            return@forEach
        }
    }

    val response = Api.command(cmd)

    log("cmd response, accepted commands= attacks=${response.acceptedCommands.attack.size} build=${response.acceptedCommands.build.size} failed=${response.errors?.size} ${response.errors}")
}

fun inBounds(it: Point2D): Boolean {
    return it.x >= 0 && it.y >= 0
}

fun Long.toDate(): String {
    Date(this).let {
        // print only  24hours time + seconds from date
        return DateFormat.getTimeInstance(DateFormat.MEDIUM).format(it)
    }
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

