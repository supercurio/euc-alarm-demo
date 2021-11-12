package supercurio.eucalarm.parsers

sealed class ParserConfig
object NoConfig : ParserConfig()
class GotwayConfig(
    val address: String,
    var voltage: Float? = null
) : ParserConfig()
