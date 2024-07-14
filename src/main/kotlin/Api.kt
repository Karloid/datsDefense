import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

var apiToken = ""

val BASE_URL = "https://games-test.datsteam.dev"
//val BASE_URL = "https://games.datsteam.dev"

object Api {

    val gson = Gson()

    // pretty print gson
    val gsonPretty = GsonBuilder().setPrettyPrinting().create()

    val okHttpClient = OkHttpClient()


    /**
     *
     * get https://games-test.datsteam.dev/rounds/zombidef
     * {
     * "gameName": "defense",
     * "now": "2021-01-01T00:00:00Z",
     * "rounds": [
     * {
     * "duration": 60,
     * "endAt": "2021-01-01T00:00:00Z",
     * "name": "Round 1",
     * "repeat": 1,
     * "startAt": "2021-01-01T00:00:00Z",
     * "status": "active"
     * }
     * ]
     * }
     */

    fun getRounds(): RoundsDto {
        throttle()
        val request = okhttp3.Request.Builder()
            .url("$BASE_URL/rounds/zombidef")
            .get()
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Auth-Token", apiToken)
            .build()

        val call = okHttpClient.newCall(request)

        val response = call.execute()

        if (response.isSuccessful.not()) {
            throw Exception("Failed to execute request response=$response body=${response.body?.string()}")
        }

        val body = response.body?.string()

        val result = gson.fromJson(body, RoundsDto::class.java) ?: throw Exception("Failed to parse response response=$response body=$body")
        return result
    }

    val lastRequest = ArrayDeque<Long>()

    /**
     * we should not fire more than 4 request per second
     */
    private fun throttle() {
        val currentTs = System.currentTimeMillis()

        // remove requests older than 1 second

        while (lastRequest.isNotEmpty() && currentTs - lastRequest.first() > 1000) {
            lastRequest.removeFirst()
        }

        if (lastRequest.size < 3) {
            lastRequest.add(currentTs)
            return
        }

        val sleepTime = 1000 - (currentTs - lastRequest.first())

        log("throttle sleep for $sleepTime")
        Thread.sleep(sleepTime)

        lastRequest.add(System.currentTimeMillis())
    }

    /**
     * put https://games.datsteam.dev/play/zombidef/participate
     *  no body
     *
     *  response:
     * {
     * "startsInSec": 300
     *
     * https://games-test.datsteam.dev/play/zombidef/participate
     * }
     */
    fun joinRound(name: String): JoinResponse {
        throttle()

        /*        val command = arrayOf(
                    "curl", "-X", "PUT",
                    "-H", "X-Auth-Token: $apiToken",
                    "https://games-test.datsteam.dev/play/zombidef/participate"
                )

                try {
                    // Execute the command
                    val process = Runtime.getRuntime().exec(command)

                    // Read the input stream to get the output of the command
                    val inputStream = process.inputStream
                    val response = inputStream.bufferedReader().readText()

                    // Print the response
                    println(response)

                    val result = gson.fromJson(response, JoinResponse::class.java) ?: throw Exception("Failed to parse response response=$response body=$response")


                    // Wait for the process to complete
                    val exitCode = process.waitFor()
                    println("Exit code: $exitCode")
                    return result

                } catch (e: Exception) {
                    e.printStackTrace()
                }*/

        val request = okhttp3.Request.Builder()
            .url("$BASE_URL/play/zombidef/participate")
            .put(okhttp3.RequestBody.create(null, ""))
            .addHeader("X-Auth-Token", apiToken)
            .build()

        val call = okHttpClient.newCall(request)

        val response = call.execute()

        if (response.isSuccessful.not()) {
            throw Exception("Failed to execute request response=$response body=${response.body?.string()}")
        }

        val body = response.body?.string()

        log(body)

        val result = gson.fromJson(body, JoinResponse::class.java) ?: throw Exception("Failed to parse response response=$response body=$body")
        return result
    }

    fun getWorldState(): WorldState {
        throttle()

        val request = okhttp3.Request.Builder()
            .url("$BASE_URL/play/zombidef/world")
            .get()
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Auth-Token", apiToken)
            .build()

        val call = okHttpClient.newCall(request)

        val response = call.execute()

        if (response.isSuccessful.not()) {
            throw Exception("Failed to execute request response=$response body=${response.body?.string()}")
        }

        val body = response.body?.string()

        // log (body)

        val result = gson.fromJson(body, WorldState::class.java) ?: throw Exception("Failed to parse response response=$response body=$body")
        return result
    }

    fun getUnits(): WorldUnits {
        throttle()
        val request = okhttp3.Request.Builder()
            .url("$BASE_URL/play/zombidef/units")
            .get()
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Auth-Token", apiToken)
            .build()

        val call = okHttpClient.newCall(request)

        val response = call.execute()

        if (response.isSuccessful.not()) {
            throw Exception("Failed to execute request response=$response body=${response.body?.string()}")
        }

        val body = response.body?.string()

        //    log(body)

        val result = gson.fromJson(body, WorldUnitsDto::class.java) ?: throw Exception("Failed to parse response response=$response body=$body")
        return result.toObj()
    }


    fun command(cmd: Command): CommandResponse {
        throttle()
        val json = gson.toJson(cmd)

        //  log(json)

        val body = okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), json)

        val request = okhttp3.Request.Builder()
            .url("$BASE_URL/play/zombidef/command")
            .post(body)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Auth-Token", apiToken)
            .build()

        val call = okHttpClient.newCall(request)

        val response = call.execute()

        if (response.isSuccessful.not()) {
            throw Exception("Failed to execute request response=$response body=${response.body?.string()}")
        }

        val bodyResponse = response.body?.string()


        //  log(bodyResponse)

        val result = gson.fromJson(bodyResponse, CommandResponse::class.java) ?: throw Exception("Failed to parse response response=$response body=$bodyResponse")

        //  log(result)

        return result
    }
}

data class CommandResponse(
    val acceptedCommands: AcceptedCommand,
    val errors: List<String>?
) {
    data class AcceptedCommand(
        val attack: List<Attack>?,
        val build: List<Point2D>?,
        val moveBase: Point2D
    ) {
        data class Attack(
            val blockId: String,
            val target: Point2D
        )
    }
}

data class Command(
    val attack: MutableList<Attack> = mutableListOf(),
    val build: MutableList<Point2D> = mutableListOf(),
    var moveBase: Point2D? = null,
) {
    data class Attack(
        val blockId: String,
        val target: Point2D
    )
}


data class Base(
    val attack: Int,
    override val health: Int,
    val id: String,
    val isHead: Boolean,
    val lastAttack: Point2D,
    val range: Int,
    val x: Int,
    val y: Int
) : HasPosAndHealth {

    private var _pos: Point2D? = null
    override val pos: Point2D
        get() {
            if (_pos == null) {
                _pos = Point2D(x, y)
            }
            return _pos!!
        }
    override val maxHealth: Int
        get() = if (isHead) 300 else 100
}

interface HasPosAndHealth {
    val pos: Point2D
    val health: Int

    val maxHealth: Int
}

data class Player(
    val enemyBlockKills: Int,
    val gameEndedAt: String,
    val gold: Int,
    val name: String,
    val points: Int,
    val zombieKills: Int
) {

}

data class EnemyBlock(
    val attack: Int,
    override var health: Int,
    val isHead: Boolean,
    val x: Int,
    val y: Int
) : HasPosAndHealth {

    var _pos: Point2D? = null
    override val pos: Point2D
        get() {
            if (_pos == null) {
                _pos = Point2D(x, y)
            }
            return _pos!!
        }
    override val maxHealth: Int
        get() = if (isHead) 300 else 100
}

data class WorldUnitsDto(
    var base: List<Base>?,
    var enemyBlocks: List<EnemyBlock>?,
    val player: Player,
    val realmName: String,
    val turn: Int,
    val turnEndsInMs: Int,
    var zombies: List<Zombie>?,
) {
    fun toObj(): WorldUnits {
        return WorldUnits(base ?: emptyList(), enemyBlocks ?: emptyList(), player, realmName, turn, turnEndsInMs, zombies ?: emptyList())
    }
}

data class WorldUnits(
    var base: List<Base>,
    var enemyBlocks: List<EnemyBlock>,
    val player: Player,
    val realmName: String,
    val turn: Int,
    val turnEndsInMs: Int,
    var zombies: List<Zombie>,
) {

    var _hashPos: HashMap<Point2D, Base>? = null
    val hashPos: HashMap<Point2D, Base>
        get() {
            if (_hashPos == null) {
                _hashPos = HashMap()
                base.forEach {
                    _hashPos!![it.pos] = it
                }
            }
            return _hashPos!!
        }
}


data class Zombie(
    val attack: Int,
    val direction: String,
    var health: Int,
    val id: String,
    val speed: Int,
    val type: String,
    val waitTurns: Int,
    val x: Int,
    val y: Int
) {
    var _pos: Point2D? = null
    val pos: Point2D
        get() {
            if (_pos == null) {
                _pos = Point2D(x, y)
            }
            return _pos!!
        }

    fun posEqual(pos: Point2D): Boolean {
        return x == pos.x && y == pos.y
    }

}

data class WorldState(
    val realmName: String,
    val zpots: List<Zpot>
) {

}

data class Zpot(
    val x: Int,
    val y: Int,
    val type: String
) {

    var _pos: Point2D? = null
    val pos: Point2D
        get() {
            if (_pos == null) {
                _pos = Point2D(x, y)
            }
            return _pos!!
        }

    fun posEqual(pos: Point2D): Boolean {
        return x == pos.x && y == pos.y
    }

}

data class JoinResponse(
    var startsInSec: Int,
) {

}

data class RoundsDto(
    var gameName: String,
    var now: String,
    var rounds: List<Round>
) {

    fun getNowAsLong(): Long {
        val stringDate = now
        return toMillis(stringDate)
    }
}

data class Round(
    var duration: Int,
    var endAt: String,
    var name: String,
    var repeat: Int,
    var startAt: String,
    var status: String
) {

    fun getEndAsLong(): Long {
        val stringDate = endAt
        return toMillis(stringDate)
    }

    fun getStartAsLong(): Long {
        val stringDate = startAt
        return toMillis(stringDate)
    }

    override fun toString(): String {
        return "Round( name='$name' duration=$duration, startAt=${getStartAsLong().toDate()} endAt=${getEndAsLong().toDate()}, repeat=$repeat, , status='$status')"
    }
}

private fun toMillis(stringDate: String) = LocalDateTime.parse(stringDate, DateTimeFormatter.ISO_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli()
