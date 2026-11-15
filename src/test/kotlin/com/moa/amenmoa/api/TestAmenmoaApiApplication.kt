package com.moa.amenmoa.api

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<AmenmoaApiApplication>().with(TestcontainersConfiguration::class).run(*args)
}
