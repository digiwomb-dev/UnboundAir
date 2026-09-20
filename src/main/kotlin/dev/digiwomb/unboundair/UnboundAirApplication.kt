package dev.digiwomb.unboundair

import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder

@SpringBootApplication
class UnboundAirApplication

fun main(args: Array<String>) {
    // No web environment: this is a command line application. Spring Boot
    // would otherwise keep a servlet container alive and the process would
    // never terminate.
    SpringApplicationBuilder(UnboundAirApplication::class.java)
        .web(WebApplicationType.NONE)
        .run(*args)
}
