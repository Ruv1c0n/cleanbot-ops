package com.cleanbot.simulator.robot

enum class Command {
    START, PAUSE, RESUME, ABORT, RETURN_HOME, RESET;

    companion object {
        fun parse(raw: String): Command? = entries.find { it.name == raw.uppercase() }
    }
}
