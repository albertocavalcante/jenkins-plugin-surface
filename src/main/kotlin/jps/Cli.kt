package jps

/** Version of this tool, recorded in every output document. */
internal const val TOOL_VERSION: String = "0.1.0"

public fun main(args: Array<String>) {
    println("jenkins-plugin-surface $TOOL_VERSION (${args.size} args)")
}
