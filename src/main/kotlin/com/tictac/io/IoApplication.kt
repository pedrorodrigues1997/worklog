package com.tictac.io

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class IoApplication

fun main(args: Array<String>) {
	runApplication<IoApplication>(*args)
}
