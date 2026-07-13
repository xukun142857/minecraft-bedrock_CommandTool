package command.plus

class CommandUserService : ICommandService.Stub() {

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            "$output"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}