import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.lang.IllegalStateException
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
    clearOldMaps()

    val roundsInfo = Api.getRounds()

    val rounds = roundsInfo.rounds.sortedBy { it.getStartAsLong() }
    val activeRound = rounds.firstOrNull { it.status == "active" }

    val nextRound = rounds.firstOrNull { it.status != "ended" }
    val nextRound5Rounds = rounds.filter { it.status != "ended" }.take(5)

    log("my round=${state.currentRound} active round $activeRound nextRound=$nextRound next5Rounds=$nextRound5Rounds")


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
        Thread.sleep(2000)
        return
    }

    log("starting round loop active=${activeRound} ")
    // play round
    while (true) {

        var worldState: WorldState? = null

        val unitsStartTs = System.currentTimeMillis()
        while (true) {
            // if more than 5 minutes of attempts then exit
            if (System.currentTimeMillis() - unitsStartTs > 5 * 60 * 1000) {
                throw IllegalStateException("more than 5 minutes of attempts of units to get units, exit")
            }

            runCatching {
                worldState = Api.getWorldState()
            }.onFailure {
                val stackTraceToString = it.stackTraceToString()
                if (stackTraceToString.contains("not participating in this round")) {
                    throw it
                }
                log("getWorldState failed, retry in  $stackTraceToString")
                Thread.sleep(1700)
            }

            if (worldState != null) {
                break
            }
        }

        val worldUnits = Api.getUnits()
        val startsTs = System.currentTimeMillis()

        log("current turn ${worldUnits.turn} turnEndsInMs=${worldUnits.turnEndsInMs}")

        val endTurn = startsTs + worldUnits.turnEndsInMs

        makeTurn(worldUnits, worldState!!, activeRound)

        val delay = endTurn - System.currentTimeMillis() + 2
        log("sleep for $delay ")
        if (delay > 0) {
            Thread.sleep(delay)
        }

        if (worldUnits.base.isEmpty()) {
            log("no base, exit")
            break
        }
    }
}

fun clearOldMaps() {
    val file = File("maps")
    runCatching {
        file.mkdir()
    }

    file.listFiles().sortedBy { it.lastModified() }
        .reversed()
        .drop(20)
        .forEach {
            runCatching {
                log("delete old maps ${it.absolutePath}")
                // delete folder with all content
                it.deleteRecursively()
            }
                .onFailure {
                    System.err.println("delete failed ${it.stackTraceToString()}")
                }
        }
}

fun createPngWithMap(worldUnits: WorldUnits, worldState: WorldState?, activeRound: Round?, response: CommandResponse) {
    // create png image draw units on it and save
    val canvasSize = 2000
    val GRID_SIZE = 10
    val image = BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_ARGB)
    val g = image.graphics

    g.clearRect(0, 0, canvasSize, canvasSize)

    g.color = java.awt.Color.BLACK
    g.fillRect(0, 0, canvasSize, canvasSize)

    g.color = java.awt.Color.WHITE

    val head = worldUnits.base.firstOrNull { it.isHead }

    val offsetX = (head?.x ?: 0) * -GRID_SIZE + canvasSize / 2
    val offsetY = (head?.x ?: 0) * -GRID_SIZE + canvasSize / 2

    worldUnits.base.forEach {
        g.color = java.awt.Color.WHITE
        if (it.isHead) {
            g.color = java.awt.Color.YELLOW
        }
        g.fillRect(it.pos.x * GRID_SIZE + offsetX, it.pos.y * GRID_SIZE + offsetY, GRID_SIZE, GRID_SIZE)
    }

    g.color = Color.RED//.setAlpha(0.5)
    worldUnits.zombies.forEach {
        g.fillRect(it.pos.x * GRID_SIZE + offsetX, it.pos.y * GRID_SIZE + offsetY, GRID_SIZE / 2, GRID_SIZE / 2)
    }

    g.color = java.awt.Color.BLUE
    worldUnits.enemyBlocks.forEach {
        g.fillRect(it.pos.x * GRID_SIZE + offsetX, it.pos.y * GRID_SIZE + offsetY, GRID_SIZE, GRID_SIZE)
    }


    worldState?.zpots?.forEach {
        if (it.type == "wall") {
            g.color = java.awt.Color.GREEN
        } else {
            g.color = java.awt.Color.PINK
        }
        g.fillRect(it.pos.x * GRID_SIZE + offsetX, it.pos.y * GRID_SIZE + offsetY, GRID_SIZE, GRID_SIZE)
    }

    // draw attack lines

    response.acceptedCommands.attack?.forEach { attack ->
        val base = worldUnits.base.firstOrNull { it.id == attack.blockId }
        if (base != null) {
            g.color = Color.RED
            g.drawLine(base.pos.x * GRID_SIZE + offsetX, base.pos.y * GRID_SIZE + offsetY, attack.target.x * GRID_SIZE + offsetX, attack.target.y * GRID_SIZE + offsetY)
        }
    }

    g.dispose()

    val folder = File("maps/${activeRound?.name ?: "unk"}")
    runCatching {
        folder.mkdir()
    }

    val file = File(folder, "map-${worldUnits.turn.toString().padStart(5, '0')}.png")

    file.outputStream().use {
        javax.imageio.ImageIO.write(image, "png", it)
    }
    log("saved map turn=${worldUnits.turn} to ${file.absolutePath}")
}

private fun Color.setAlpha(d: Double): Color {
    return Color(red, green, blue, (d * 255).toInt())
}

fun makeTurn(units: WorldUnits, worldState: WorldState, activeRound: Round?) {
    Thread.sleep(500)
    var myGold = units.player.gold

    val cmd = Command()

    val costBase = 1

    log("my states gold=${myGold} base=${units.base?.size} zombies=${units.zombies?.size} enemyBlocks=${units.enemyBlocks?.size} zpots=${worldState.zpots.size}")

    val headBase = units.base.firstOrNull() { it.isHead } ?: return

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

    allPossiblePlaces
        .distinct()
        .take(myGold).forEach { posToBuild ->
            cmd.build.add(posToBuild)
        }

    // kill zombies


    units.base.forEach { base ->
        val pos = base.pos

        val range = base.range

        var zombiesInRange = units.zombies.filter {
            it.health > 0 &&
                    it.pos.euclidianDistance2(pos) <= range * range && canAttackMyUnits(units, it)
        }
            //.minByOrNull { it.pos.euclidianDistance2(pos) }
            .minByOrNull { it.health }

        zombiesInRange?.let { z ->
            cmd.attack.add(Command.Attack(base.id, z.pos))
            z.health -= base.attack
            return@forEach
        }

        val enemyBlock = units.enemyBlocks.filter { it.health > 0 && it.pos.euclidianDistance2(pos) <= range * range }
            .minByOrNull { it.health }

        enemyBlock?.let { z ->
            cmd.attack.add(Command.Attack(base.id, z.pos))
            z.health -= base.attack
            return@forEach
        }


        zombiesInRange = units.zombies.filter {
            it.type == "bomber" &&
                    it.health > 0 &&
                    it.pos.euclidianDistance2(pos) <= range * range
        }
            //.minByOrNull { it.pos.euclidianDistance2(pos) }
            .minByOrNull { it.health }

        zombiesInRange?.let { z ->
            cmd.attack.add(Command.Attack(base.id, z.pos))
            z.health -= base.attack
            return@forEach
        }

        zombiesInRange = units.zombies.filter {
            it.health > 0 && it.pos.euclidianDistance2(pos) <= range * range
        }
            .minByOrNull { it.health }

        zombiesInRange?.let { z ->
            cmd.attack.add(Command.Attack(base.id, z.pos))
            z.health -= base.attack
            return@forEach
        }
    }

    var currentBase = units.base.firstOrNull { it.isHead }


    val response = Api.command(cmd)

    createPngWithMap(units, worldState, activeRound, response)

    log("cmd response, accepted commands= attacks=${response.acceptedCommands.attack?.size} build=${response.acceptedCommands.build?.size} failed=${response.errors?.size} ${response.errors}")
}

fun canAttackMyUnits(units: WorldUnits, zombie: Zombie): Boolean {
    val direction = when (zombie.direction) {
        "up" -> {
            Point2D.UP
        }

        "down" -> {
            Point2D.DOWN
        }

        "left" -> {
            Point2D.LEFT
        }

        "right" -> {
            Point2D.RIGHT
        }

        else -> {
            Point2D.ZERO
        }
    }

    val zombiePos = zombie.pos.copy()

    repeat(30) { it ->
        zombiePos.add(direction)
        if (units.hashPos[zombiePos] != null) {
            return true
        }

    }

    // 0 0 , 0 1
    // 1 0 , 1 1

    return false
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

